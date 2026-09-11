package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.model.routing.RoutePlan
import ca.floo.roadtrip.support.RoutingException
import kotlinx.serialization.json.Json

/** What planning a route came to. The route maps each arm to a status. */
internal sealed interface RoutePlanResult {
    data class Planned(
        val plan: RoutePlan,
    ) : RoutePlanResult

    /** Mapbox rejects identical adjacent waypoints with code:"InvalidInput". */
    data class DuplicateWaypoints(
        val index: Int,
    ) : RoutePlanResult

    data class DirectionsUnavailable(
        val detail: String,
    ) : RoutePlanResult

    data class CorridorUnavailable(
        val detail: String,
    ) : RoutePlanResult
}

/**
 * The `/api/route` use case: directions, then optionally the server-side
 * corridor polygon around them. Owns the vendor rule about adjacent duplicates
 * so the route never has to know why Mapbox would refuse.
 */
internal class RoutePlanService(
    private val routeCache: RouteCache,
    private val corridorService: RouteCorridorService,
) {
    val configured: Boolean get() = routeCache.configured

    suspend fun plan(
        waypoints: List<Pair<Double, Double>>,
        corridorRadiusMiles: Double?,
    ): RoutePlanResult {
        duplicateAdjacentIndex(waypoints)?.let { return RoutePlanResult.DuplicateWaypoints(it) }

        val directions =
            try {
                routeCache.directions(waypoints)
            } catch (e: RoutingException) {
                return RoutePlanResult.DirectionsUnavailable(e.message.orEmpty())
            }

        val corridor =
            corridorRadiusMiles?.let { radiusMiles ->
                try {
                    Json.parseToJsonElement(
                        corridorService.bufferedPolygonGeoJson(
                            lineGeoJson = lineStringGeoJson(directions.coordinates),
                            radiusMiles = radiusMiles,
                        ),
                    )
                } catch (e: RoutingException) {
                    return RoutePlanResult.CorridorUnavailable(e.message.orEmpty())
                }
            }

        return RoutePlanResult.Planned(
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = corridorRadiusMiles,
                corridorGeoJson = corridor,
            ),
        )
    }

    private fun duplicateAdjacentIndex(waypoints: List<Pair<Double, Double>>): Int? =
        (1 until waypoints.size).firstOrNull { waypoints[it] == waypoints[it - 1] }
}
