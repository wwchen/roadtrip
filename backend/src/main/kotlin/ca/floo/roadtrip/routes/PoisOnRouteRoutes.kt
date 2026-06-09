package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.RoutingException
import ca.floo.roadtrip.models.api.PoisOnRouteRequestSchema
import ca.floo.roadtrip.models.api.WaypointSchema
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.OnRoutePoiRepo
import ca.floo.roadtrip.repo.OnRouteRow
import ca.floo.roadtrip.repo.RouteCache
import ca.floo.roadtrip.repo.RouteCorridorRepo
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
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
fun Route.poisOnRouteRoutes(
    ctx: DSLContext,
    routeCache: RouteCache,
    registry: PoiRegistry,
) {
    val defaultCategories: List<String> =
        registry
            .enabledPoiData()
            .map { it.category }
            .toSet()
            .toList()
    val onRoutePoiRepo = OnRoutePoiRepo(ctx)
    val routeCorridorRepo = RouteCorridorRepo(ctx)

    post("/api/pois/on-route", {
        tags = listOf("poi")
        summary = "Slim POIs inside a buffered route corridor (no viewport, no truncation)"
        description =
            "Body: { waypoints: [{lat,lng}…2..$MAX_ROUTE_WAYPOINTS], " +
            "radius_miles: ${MIN_ROUTE_CORRIDOR_RADIUS_MILES}..$MAX_ROUTE_CORRIDOR_RADIUS_MILES, categories? }. " +
            "Returns a GeoJSON FeatureCollection ordered by route_km. " +
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
                description = "FeatureCollection of slim features (+ route_km), sorted by route_km."
                body<String> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed body, bad waypoints, or radius out of range."
                body<String> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Route lookup failed (Mapbox unreachable / cache miss)."
                body<String> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val bodyText = call.receiveText()
        val req =
            try {
                parseOnRouteRequest(bodyText)
            } catch (e: Exception) {
                call.respondText(
                    onRouteJson.encodeToString(
                        ErrorResponseDto(error = "bad_request", detail = e.message ?: "parse failed"),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        val polylineCoords =
            try {
                val pairs = req.waypoints.map { it.lng to it.lat }
                routeCache.directions(pairs).coordinates
            } catch (e: RoutingException) {
                onRouteLog.warn("on-route lookup failed: {}", e.message)
                call.respondText(
                    onRouteJson.encodeToString(ErrorResponseDto(error = "routing_unavailable")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
        val corridorLineGeoJson = lineStringGeoJson(polylineCoords)

        val cats = req.categories ?: defaultCategories
        val rows =
            if (cats.isEmpty()) {
                emptyList()
            } else {
                try {
                    val corridorPolygonGeoJson =
                        routeCorridorRepo.bufferedPolygonGeoJson(
                            corridorLineGeoJson,
                            routeCorridorRadiusMeters(req.radiusMiles),
                        )
                    onRoutePoiRepo.fetch(cats, corridorLineGeoJson, corridorPolygonGeoJson)
                } catch (e: DataAccessException) {
                    val cause = e.cause?.message.orEmpty()
                    if (cause.contains("TopologyException")) {
                        onRouteLog.warn("on-route GEOS topology fault, returning empty: {}", cause)
                        emptyList()
                    } else {
                        throw e
                    }
                }
            }

        call.respondText(
            buildOnRouteFeatureCollection(rows),
            ContentType.Application.Json,
        )
    }
}

private data class OnRouteRequest(
    val waypoints: List<Waypoint>,
    val radiusMiles: Double,
    val categories: List<String>?,
)

private data class Waypoint(
    val lat: Double,
    val lng: Double,
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
                }?.takeIf { it.isNotEmpty() }
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
    fun validated(index: Int): Waypoint {
        val parsedLat = lat ?: error("waypoint[$index].lat is missing or not a number")
        val parsedLng = lng ?: error("waypoint[$index].lng is missing or not a number")
        require(parsedLat in -90.0..90.0) { "waypoint[$index].lat out of range" }
        require(parsedLng in -180.0..180.0) { "waypoint[$index].lng out of range" }
        return Waypoint(lat = parsedLat, lng = parsedLng)
    }
}

private fun parseOnRouteRequest(bodyText: String): OnRouteRequest = onRouteJson.decodeFromString<OnRouteRequestDto>(bodyText).validated()

@Serializable
private data class ErrorResponseDto(
    val error: String,
    val detail: String? = null,
)

@Serializable
private data class OnRouteFeatureCollectionDto(
    val type: String = "FeatureCollection",
    val features: List<OnRouteFeatureDto>,
) {
    companion object {
        fun fromRows(rows: List<OnRouteRow>): OnRouteFeatureCollectionDto =
            OnRouteFeatureCollectionDto(features = rows.map { OnRouteFeatureDto.fromRow(it) })
    }
}

@Serializable
private data class OnRouteFeatureDto(
    val type: String = "Feature",
    val id: Long,
    val geometry: PointGeometryDto,
    val properties: OnRouteFeaturePropertiesDto,
) {
    companion object {
        fun fromRow(row: OnRouteRow): OnRouteFeatureDto =
            OnRouteFeatureDto(
                id = row.id,
                geometry = PointGeometryDto(coordinates = listOf(row.lng, row.lat)),
                properties =
                    OnRouteFeaturePropertiesDto(
                        category = row.category,
                        subcategory = row.subcategory,
                        routeKm = row.routeKm,
                    ),
            )
    }
}

@Serializable
private data class PointGeometryDto(
    val type: String = "Point",
    val coordinates: List<Double>,
)

@Serializable
private data class OnRouteFeaturePropertiesDto(
    val category: String,
    val subcategory: String? = null,
    @SerialName("route_km") val routeKm: Double,
)

/**
 * On-route FeatureCollection. Same per-feature shape as the bbox endpoint
 * (id + Point + category[+subcategory]) plus a `route_km` property so the
 * FE can sort or label without re-projecting on the client.
 */
internal fun buildOnRouteFeatureCollection(rows: List<OnRouteRow>): String =
    onRouteJson.encodeToString(OnRouteFeatureCollectionDto.fromRows(rows))
