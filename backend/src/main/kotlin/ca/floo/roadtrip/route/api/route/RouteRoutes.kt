package ca.floo.roadtrip.route.api.route

import ca.floo.roadtrip.config.RouteConfig
import ca.floo.roadtrip.model.api.RouteErrorDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.OptionalQuery
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.optionalDoubleQuery
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.service.api.RouteResponseMapper
import ca.floo.roadtrip.service.routing.RoutePlanResult
import ca.floo.roadtrip.service.routing.RoutePlanService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * GET /api/route?coords=lng,lat;lng,lat;...
 *
 * Backend proxy for the Mapbox Directions API. The token stays server-side, and
 * the corridor radius is validated against [RouteConfig] rather than trusted.
 */
internal fun Route.routeRoutes(
    routePlanService: RoutePlanService,
    routeResponseMapper: RouteResponseMapper,
    routeConfig: RouteConfig,
) {
    route("/api") {
        get("/route") {
            if (!routePlanService.configured) {
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

            when (val result = routePlanService.plan(coords, corridorRadiusMiles)) {
                is RoutePlanResult.DuplicateWaypoints ->
                    call.respondRouteError(
                        error = "duplicate_adjacent",
                        detail = "points ${result.index} and ${result.index - 1} are identical",
                        status = HttpStatusCode.BadRequest,
                    )
                is RoutePlanResult.DirectionsUnavailable ->
                    call.respondRouteError(
                        error = "routing_unavailable",
                        detail = result.detail,
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                is RoutePlanResult.CorridorUnavailable ->
                    call.respondRouteError(
                        error = "corridor_unavailable",
                        detail = result.detail,
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                is RoutePlanResult.Planned ->
                    call.respondEncodedJson(routeResponseMapper.featureCollection(result.plan))
            }
        }.describeApi(
            tag = "route",
            summary = "Driving route through the given waypoints, as a GeoJSON FeatureCollection",
            description =
                "`coords` is `lng,lat;lng,lat[;...]`, 2..${routeConfig.maxWaypoints} points. " +
                    "The single LineString feature carries `distance_m`, `duration_s` and `legs[]` in its " +
                    "properties. `radius_miles` optionally buffers the corridor, " +
                    "${routeConfig.minCorridorRadiusMiles}..${routeConfig.maxCorridorRadiusMiles}.",
        ).access(RouteAccess.Anonymous)
    }
}

private suspend fun ApplicationCall.respondRouteError(
    error: String,
    detail: String,
    status: HttpStatusCode,
) {
    respondEncodedJson(RouteErrorDto(error = error, detail = detail), status)
}
