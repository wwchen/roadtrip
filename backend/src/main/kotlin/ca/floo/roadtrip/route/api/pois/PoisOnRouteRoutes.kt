package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.RouteConfig
import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.poi.PointGeometrySchema
import ca.floo.roadtrip.model.api.poi.PoisOnRouteFeaturePropertiesSchema
import ca.floo.roadtrip.model.api.poi.PoisOnRouteFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoisOnRouteResponseSchema
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.poi.PoiRow
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.mapCatching
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.poi.OnRouteWaypoint
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.canonicalPoiCategories
import ca.floo.roadtrip.support.RoutingException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val onRouteLog = LoggerFactory.getLogger("PoisOnRouteRoutes")

// POST /api/pois/on-route
//
// Returns every POI inside the buffered route corridor — no viewport bound,
// no per-category cap. Drives the trip planner's "campgrounds along route"
// card list, which the user wants to scan end-to-end instead of pan-by-pan.
internal fun Route.poisOnRouteRoutes(
    poisOnRouteService: PoisOnRouteService,
    routeConfig: RouteConfig,
) {
    route("/api") {
        route("/pois") {
            post("/on-route") {
                val req =
                    when (
                        val body =
                            call
                                .receiveJsonBody<OnRouteRequestDto>()
                                .mapCatching { parseOnRouteRequest(it, routeConfig) }
                    ) {
                        is RouteBodyResult.Invalid -> {
                            call.respondOnRouteJson(
                                ApiErrorSchema(error = "bad_request", detail = body.detail ?: "parse failed"),
                                HttpStatusCode.BadRequest,
                            )
                            return@post
                        }
                        is RouteBodyResult.Valid -> body.value
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
            }.describeApi(
                tag = "poi",
                summary = "Slim POIs inside a buffered route corridor (no viewport, no truncation)",
                description =
                    "Body: { waypoints: [{lat,lng}...2..${routeConfig.maxWaypoints}], " +
                        "radius_miles: ${routeConfig.minCorridorRadiusMiles}..${routeConfig.maxCorridorRadiusMiles}, categories? }. " +
                        "Returns every matching POI as a slim GeoJSON FeatureCollection. " +
                        "Backed by RouteCache; the FE typically primes it via /api/route just before this call.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

private data class OnRouteRequest(
    val waypoints: List<OnRouteWaypoint>,
    val radiusMiles: Double,
    val categories: List<String>?,
)

@Serializable
private class OnRouteRequestDto(
    val waypoints: List<WaypointDto> = emptyList(),
    @SerialName("radius_miles") val radiusMiles: Double? = null,
    val categories: List<String>? = null,
) {
    fun validated(routeConfig: RouteConfig): OnRouteRequest {
        require(waypoints.size in 2..routeConfig.maxWaypoints) {
            "waypoints must have 2..${routeConfig.maxWaypoints} entries (got ${waypoints.size})"
        }
        val radius = radiusMiles ?: error("radius_miles is missing or not a number")
        require(radius in routeConfig.minCorridorRadiusMiles..routeConfig.maxCorridorRadiusMiles) {
            "radius_miles must be in [${routeConfig.minCorridorRadiusMiles}, ${routeConfig.maxCorridorRadiusMiles}] (got $radius)"
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
private class WaypointDto(
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

private fun parseOnRouteRequest(
    dto: OnRouteRequestDto,
    routeConfig: RouteConfig,
): OnRouteRequest = dto.validated(routeConfig)

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

private suspend inline fun <reified T> ApplicationCall.respondOnRouteJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondEncodedJson(value, status)
}
