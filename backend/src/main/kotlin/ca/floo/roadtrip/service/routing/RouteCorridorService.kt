package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.repo.RouteCorridorRepo

internal class RouteCorridorService(
    private val routeCorridorRepo: RouteCorridorRepo,
) {
    fun bufferedPolygonGeoJson(
        lineGeoJson: String,
        radiusMiles: Double,
    ): String =
        routeCorridorRepo.bufferedPolygonGeoJson(
            lineGeoJson = lineGeoJson,
            radiusMeters = routeCorridorRadiusMeters(radiusMiles),
        )
}
