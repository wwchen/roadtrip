package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.RouteResponse
import ca.floo.roadtrip.client.RoutingException
import ca.floo.roadtrip.repo.RouteCache
import ca.floo.roadtrip.repo.RouteCorridorRepo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

@OptIn(ExperimentalSerializationApi::class)
private val routeJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * GET /api/route?coords=lng,lat;lng,lat;...
 *
 * Backend proxy for Mapbox Directions API. Token stays server-side.
 *
 * Returns:
 *   200 { type:"FeatureCollection", features: [ LineString feature with
 *         distance_m, duration_s, legs[] in properties ] }
 *   400 for malformed coords / wrong number of waypoints
 *   503 when MAPBOX_TOKEN unset or upstream fails
 */
fun Route.routeRoutes(
    routeCache: RouteCache,
    ctx: DSLContext,
) {
    val routeCorridorRepo = RouteCorridorRepo(ctx)

    get("/api/route") {
        if (!routeCache.configured) {
            call.respondRouteError(
                error = "routing_unavailable",
                detail = "MAPBOX_TOKEN not set",
                status = HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val raw = call.request.queryParameters["coords"].orEmpty()
        val pieces = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        if (pieces.size < 2) {
            call.respondRouteError(
                error = "too_few_points",
                detail = "need >= 2 waypoints in coords=lng,lat;lng,lat[;...]",
                status = HttpStatusCode.BadRequest,
            )
            return@get
        }
        if (pieces.size > MAX_ROUTE_WAYPOINTS) {
            call.respondRouteError(
                error = "too_many_points",
                detail = "max $MAX_ROUTE_WAYPOINTS waypoints",
                status = HttpStatusCode.BadRequest,
            )
            return@get
        }

        val coords = mutableListOf<Pair<Double, Double>>()
        for ((i, p) in pieces.withIndex()) {
            val parts = p.split(",")
            if (parts.size != 2) {
                call.respondRouteError(
                    error = "bad_coords",
                    detail = "point $i: '$p' is not 'lng,lat'",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            val lng = parts[0].toDoubleOrNull()
            val lat = parts[1].toDoubleOrNull()
            if (lng == null || lat == null) {
                call.respondRouteError(
                    error = "bad_coords",
                    detail = "point $i: '$p' is not 'lng,lat'",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            if (lng !in -180.0..180.0 || lat !in -90.0..90.0) {
                call.respondRouteError(
                    error = "out_of_range",
                    detail = "point $i out of lng/lat range",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            coords.add(lng to lat)
        }
        val corridorRadiusMiles =
            call.request.queryParameters["radius_miles"]?.let { rawRadius ->
                val radius =
                    rawRadius.toDoubleOrNull()
                        ?: return@get call.respondRouteError(
                            error = "bad_radius",
                            detail = "radius_miles must be a number",
                            status = HttpStatusCode.BadRequest,
                        )
                if (radius !in MIN_ROUTE_CORRIDOR_RADIUS_MILES..MAX_ROUTE_CORRIDOR_RADIUS_MILES) {
                    return@get call.respondRouteError(
                        error = "bad_radius",
                        detail = "radius_miles must be in [$MIN_ROUTE_CORRIDOR_RADIUS_MILES, $MAX_ROUTE_CORRIDOR_RADIUS_MILES]",
                        status = HttpStatusCode.BadRequest,
                    )
                }
                radius
            }
        // Mapbox rejects identical adjacent waypoints with code:"InvalidInput".
        // Catch it before the round-trip.
        for (i in 1 until coords.size) {
            if (coords[i] == coords[i - 1]) {
                call.respondRouteError(
                    error = "duplicate_adjacent",
                    detail = "points $i and ${i - 1} are identical",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
        }

        val response =
            try {
                routeCache.directions(coords)
            } catch (e: RoutingException) {
                call.respondRouteError(
                    error = "routing_unavailable",
                    detail = e.message ?: "",
                    status = HttpStatusCode.ServiceUnavailable,
                )
                return@get
            }

        val routeLineGeoJson = lineStringGeoJson(response.coordinates)
        val corridorPolygonGeoJson =
            corridorRadiusMiles?.let { radiusMiles ->
                try {
                    routeCorridorRepo.bufferedPolygonGeoJson(
                        routeLineGeoJson,
                        routeCorridorRadiusMeters(radiusMiles),
                    )
                } catch (e: DataAccessException) {
                    call.respondRouteError(
                        error = "corridor_unavailable",
                        detail = e.message ?: "",
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                    return@get
                }
            }

        call.respondRouteJson(
            routeResponseFeatureCollection(
                response = response,
                waypoints = coords,
                corridorRadiusMiles = corridorRadiusMiles,
                corridorPolygonGeoJson = corridorPolygonGeoJson,
            ),
        )
    }
}

internal fun routeResponseFeatureCollection(
    response: RouteResponse,
    waypoints: List<Pair<Double, Double>>,
    corridorRadiusMiles: Double? = null,
    corridorPolygonGeoJson: String? = null,
): RouteFeatureCollectionDto {
    val features =
        mutableListOf(
            routeJson.encodeToJsonElement(
                RouteFeatureDto(
                    geometry = RouteLineGeometryDto(coordinates = response.coordinates),
                    properties =
                        RoutePropertiesDto(
                            distanceMeters = response.distanceMeters,
                            durationSeconds = response.durationSeconds,
                            legs =
                                response.legs.map { leg ->
                                    RouteLegDto(
                                        distanceMeters = leg.distanceMeters,
                                        durationSeconds = leg.durationSeconds,
                                    )
                                },
                            waypoints = waypoints.map { (lng, lat) -> listOf(lng, lat) },
                        ),
                ),
            ),
        )
    if (corridorRadiusMiles != null && corridorPolygonGeoJson != null) {
        features +=
            routeJson.encodeToJsonElement(
                CorridorFeatureDto(
                    geometry = Json.parseToJsonElement(corridorPolygonGeoJson),
                    properties =
                        CorridorPropertiesDto(
                            radiusMiles = corridorRadiusMiles,
                        ),
                ),
            )
    }
    return RouteFeatureCollectionDto(features = features)
}

@Serializable
internal data class RouteFeatureCollectionDto(
    val type: String = "FeatureCollection",
    val features: List<JsonElement>,
)

@Serializable
private data class RouteFeatureDto(
    val type: String = "Feature",
    val geometry: RouteLineGeometryDto,
    val properties: RoutePropertiesDto,
)

@Serializable
private data class CorridorFeatureDto(
    val type: String = "Feature",
    val geometry: JsonElement,
    val properties: CorridorPropertiesDto,
)

@Serializable
private data class RouteLineGeometryDto(
    val type: String = "LineString",
    val coordinates: List<List<Double>>,
)

@Serializable
private data class RoutePropertiesDto(
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("duration_s") val durationSeconds: Double,
    val legs: List<RouteLegDto>,
    val waypoints: List<List<Double>>,
)

@Serializable
private data class RouteLegDto(
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("duration_s") val durationSeconds: Double,
)

@Serializable
private data class CorridorPropertiesDto(
    val role: String = "corridor",
    @SerialName("radius_miles") val radiusMiles: Double,
)

@Serializable
private data class RouteErrorDto(
    val error: String,
    val detail: String,
)

private suspend fun ApplicationCall.respondRouteError(
    error: String,
    detail: String,
    status: HttpStatusCode,
) {
    respondRouteJson(RouteErrorDto(error = error, detail = detail), status)
}

internal fun encodeRouteJson(value: RouteFeatureCollectionDto): String = routeJson.encodeToString(value)

private suspend inline fun <reified T> ApplicationCall.respondRouteJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(routeJson.encodeToString(value), ContentType.Application.Json, status)
}
