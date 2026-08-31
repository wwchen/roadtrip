package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.support.RoutingException
import org.jooq.exception.DataAccessException

private const val CORRIDOR_UNAVAILABLE = "corridor_unavailable"

internal class RouteCorridorService(
    private val routeCorridorRepo: RouteCorridorRepo,
) {
    /** @throws RoutingException when PostGIS cannot buffer the line; callers see a domain failure, not jOOQ's. */
    fun bufferedPolygonGeoJson(
        lineGeoJson: String,
        radiusMiles: Double,
    ): String =
        try {
            routeCorridorRepo.bufferedPolygonGeoJson(
                lineGeoJson = lineGeoJson,
                radiusMeters = routeCorridorRadiusMeters(radiusMiles),
            )
        } catch (e: DataAccessException) {
            throw RoutingException(CORRIDOR_UNAVAILABLE, e)
        }
}
