package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.mapbox.RoutingException
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.PointGeometrySchema
import ca.floo.roadtrip.models.api.PoisOnRouteFeaturePropertiesSchema
import ca.floo.roadtrip.models.api.PoisOnRouteFeatureSchema
import ca.floo.roadtrip.models.api.PoisOnRouteRequestSchema
import ca.floo.roadtrip.models.api.PoisOnRouteResponseSchema
import ca.floo.roadtrip.models.api.WaypointSchema
import ca.floo.roadtrip.models.domain.PoiRow
import ca.floo.roadtrip.service.api.OnRouteWaypoint
import ca.floo.roadtrip.service.api.PoisOnRouteService
import ca.floo.roadtrip.service.api.canonicalPoiCategories
import ca.floo.roadtrip.service.routing.MAX_ROUTE_CORRIDOR_RADIUS_MILES
import ca.floo.roadtrip.service.routing.MAX_ROUTE_WAYPOINTS
import ca.floo.roadtrip.service.routing.MIN_ROUTE_CORRIDOR_RADIUS_MILES
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@OptIn(ExperimentalSerializationApi::class)
private val onRouteJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

private val onRouteLog = LoggerFactory.getLogger("PoisOnRouteRoutes")

// POST /api/pois/on-route
//
// Returns every POI inside the buffered route corridor — no viewport bound,
// no per-category cap. Drives the trip planner's "campgrounds along route"
// card list, which the user wants to scan end-to-end instead of pan-by-pan.
internal fun Route.poisOnRouteRoutes(poisOnRouteService: PoisOnRouteService) {
    post("/api/pois/on-route", {
        tags = listOf("poi")
        summary = "Slim POIs inside a buffered route corridor (no viewport, no truncation)"
        description =
            "Body: { waypoints: [{lat,lng}…2..$MAX_ROUTE_WAYPOINTS], " +
            "radius_miles: ${MIN_ROUTE_CORRIDOR_RADIUS_MILES}..$MAX_ROUTE_CORRIDOR_RADIUS_MILES, categories? }. " +
            "Returns every matching POI as a slim GeoJSON FeatureCollection. " +
            "Backed by RouteCache; the FE typically primes it via /api/route just before this call."
        request {
            body<PoisOnRouteRequestSchema> {
                mediaTypes(ContentType.Application.Json)
                example("Vancouver → Seattle, 5mi corridor, campgrounds") {
                    value =
                        PoisOnRouteRequestSchema(
                            waypoints =
                                listOf(
                                    WaypointSchema(lat = 49.28, lng = -123.10),
                                    WaypointSchema(lat = 47.61, lng = -122.33),
                                ),
                            radius_miles = 5.0,
                            categories = listOf("campground"),
                        )
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "FeatureCollection of slim features inside the buffered route corridor."
                body<PoisOnRouteResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed body, bad waypoints, or radius out of range."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Route lookup failed (Mapbox unreachable / cache miss)."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val bodyText = call.receiveText()
        val req =
            try {
                parseOnRouteRequest(bodyText)
            } catch (e: Exception) {
                call.respondOnRouteJson(
                    ApiErrorSchema(error = "bad_request", detail = e.message ?: "parse failed"),
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        val rows =
            try {
                poisOnRouteService.poisOnRoute(
                    waypoints = req.waypoints,
                    radiusMiles = req.radiusMiles,
                    categories = req.categories,
                )
            } catch (e: RoutingException) {
                onRouteLog.warn("on-route lookup failed: {}", e.message)
                call.respondOnRouteJson(
                    ApiErrorSchema(error = "routing_unavailable"),
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }

        call.respondOnRouteJson(onRouteFeatureCollection(rows))
    }
}

private data class OnRouteRequest(
    val waypoints: List<OnRouteWaypoint>,
    val radiusMiles: Double,
    val categories: List<String>?,
)

@Serializable
private data class OnRouteRequestDto(
    val waypoints: List<WaypointDto> = emptyList(),
    @SerialName("radius_miles") val radiusMiles: Double? = null,
    val categories: List<String>? = null,
) {
    fun validated(): OnRouteRequest {
        require(waypoints.size in 2..MAX_ROUTE_WAYPOINTS) {
            "waypoints must have 2..$MAX_ROUTE_WAYPOINTS entries (got ${waypoints.size})"
        }
        val radius = radiusMiles ?: error("radius_miles is missing or not a number")
        require(radius in MIN_ROUTE_CORRIDOR_RADIUS_MILES..MAX_ROUTE_CORRIDOR_RADIUS_MILES) {
            "radius_miles must be in [$MIN_ROUTE_CORRIDOR_RADIUS_MILES, $MAX_ROUTE_CORRIDOR_RADIUS_MILES] (got $radius)"
        }
        val parsedCategories =
            categories
                ?.mapNotNull {
                    it.trim().takeIf { category -> category.isNotEmpty() }
                }?.let(::canonicalPoiCategories)
                ?.takeIf { it.isNotEmpty() }
        return OnRouteRequest(
            waypoints = waypoints.mapIndexed { index, waypoint -> waypoint.validated(index) },
            radiusMiles = radius,
            categories = parsedCategories,
        )
    }
}

@Serializable
private data class WaypointDto(
    val lat: Double? = null,
    val lng: Double? = null,
) {
    fun validated(index: Int): OnRouteWaypoint {
        val parsedLat = lat ?: error("waypoint[$index].lat is missing or not a number")
        val parsedLng = lng ?: error("waypoint[$index].lng is missing or not a number")
        require(parsedLat in -90.0..90.0) { "waypoint[$index].lat out of range" }
        require(parsedLng in -180.0..180.0) { "waypoint[$index].lng out of range" }
        return OnRouteWaypoint(lat = parsedLat, lng = parsedLng)
    }
}

private fun parseOnRouteRequest(bodyText: String): OnRouteRequest = onRouteJson.decodeFromString<OnRouteRequestDto>(bodyText).validated()

/**
 * On-route FeatureCollection. Same per-feature shape as the bbox endpoint
 * (id + Point + category[+subcategory]), but without bbox-only metadata
 * such as `truncated`.
 */
internal fun onRouteFeatureCollection(rows: List<PoiRow>): PoisOnRouteResponseSchema =
    PoisOnRouteResponseSchema(features = rows.map(::onRouteFeature))

private fun onRouteFeature(row: PoiRow): PoisOnRouteFeatureSchema =
    PoisOnRouteFeatureSchema(
        id = row.id,
        geometry = PointGeometrySchema(coordinates = listOf(row.lng, row.lat)),
        properties =
            PoisOnRouteFeaturePropertiesSchema(
                category = row.category,
                subcategory = row.subcategory,
                agency = row.agency,
            ),
    )

internal fun encodeOnRouteJson(value: PoisOnRouteResponseSchema): String = onRouteJson.encodeToString(value)

private suspend inline fun <reified T> ApplicationCall.respondOnRouteJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(onRouteJson.encodeToString(value), ContentType.Application.Json, status)
}
