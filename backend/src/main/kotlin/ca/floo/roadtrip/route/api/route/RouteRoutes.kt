package ca.floo.roadtrip.route.api.route

import ca.floo.roadtrip.config.RouteConfig
import ca.floo.roadtrip.model.api.CorridorFeatureDto
import ca.floo.roadtrip.model.api.CorridorPropertiesDto
import ca.floo.roadtrip.model.api.RouteErrorDto
import ca.floo.roadtrip.model.api.RouteFeatureCollectionDto
import ca.floo.roadtrip.model.api.RouteFeatureDto
import ca.floo.roadtrip.model.api.RouteLegDto
import ca.floo.roadtrip.model.api.RouteLineGeometryDto
import ca.floo.roadtrip.model.api.RoutePropertiesDto
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.route.common.OptionalQuery
import ca.floo.roadtrip.route.common.optionalDoubleQuery
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import ca.floo.roadtrip.service.routing.lineStringGeoJson
import ca.floo.roadtrip.support.RoutingException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
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
 *   503 when roadtrip.mapbox.token is unset or upstream fails
 */
internal fun Route.routeRoutes(
    routeCache: RouteCache,
    routeCorridorService: RouteCorridorService,
    routeConfig: RouteConfig,
) {
    route("/api") {
        get("/route") {
            if (!routeCache.configured) {
                call.respondRouteError(
                    error = "routing_unavailable",
                    detail = "roadtrip.mapbox.token not set",
                    status = HttpStatusCode.ServiceUnavailable,
                )
                return@get
            }

            val raw = call.trimmedQuery("coords")
            val pieces = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }

            if (pieces.size < 2) {
                call.respondRouteError(
                    error = "too_few_points",
                    detail = "need >= 2 waypoints in coords=lng,lat;lng,lat[;...]",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            if (pieces.size > routeConfig.maxWaypoints) {
                call.respondRouteError(
                    error = "too_many_points",
                    detail = "max ${routeConfig.maxWaypoints} waypoints",
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
                when (val radiusQuery = call.optionalDoubleQuery("radius_miles")) {
                    OptionalQuery.Missing -> null
                    is OptionalQuery.Invalid ->
                        return@get call.respondRouteError(
                            error = "bad_radius",
                            detail = "radius_miles must be a number",
                            status = HttpStatusCode.BadRequest,
                        )
                    is OptionalQuery.Parsed -> {
                        val radius = radiusQuery.value
                        if (radius !in routeConfig.minCorridorRadiusMiles..routeConfig.maxCorridorRadiusMiles) {
                            return@get call.respondRouteError(
                                error = "bad_radius",
                                detail =
                                    "radius_miles must be in " +
                                        "[${routeConfig.minCorridorRadiusMiles}, ${routeConfig.maxCorridorRadiusMiles}]",
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        radius
                    }
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
                        routeCorridorService.bufferedPolygonGeoJson(
                            routeLineGeoJson,
                            radiusMiles,
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
    respondEncodedJson(routeJson, value, status)
}
