package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.models.domain.poi.PoiRow
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import ca.floo.roadtrip.service.routing.lineStringGeoJson
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory

private val poisOnRouteLog = LoggerFactory.getLogger("PoisOnRouteService")

internal class PoisOnRouteService(
    private val routeCache: RouteCache,
    private val routeCorridorService: RouteCorridorService,
    private val poiService: PoiReader,
    private val defaultCategories: List<String> = DEFAULT_POI_TYPES,
) {
    suspend fun poisOnRoute(
        waypoints: List<OnRouteWaypoint>,
        radiusMiles: Double,
        categories: List<String>?,
    ): List<PoiRow> {
        val requestedCategories = categories ?: defaultCategories
        if (requestedCategories.isEmpty()) return emptyList()

        val lineGeoJson =
            lineStringGeoJson(
                routeCache
                    .directions(waypoints.map { it.lng to it.lat })
                    .coordinates,
            )
        return try {
            val polygonGeoJson =
                routeCorridorService.bufferedPolygonGeoJson(
                    lineGeoJson = lineGeoJson,
                    radiusMiles = radiusMiles,
                )
            poiService.poisWithinPolygon(
                polygonGeoJson = polygonGeoJson,
                categories = requestedCategories,
            )
        } catch (e: DataAccessException) {
            val cause = e.cause?.message.orEmpty()
            if (cause.contains("TopologyException")) {
                poisOnRouteLog.warn("on-route GEOS topology fault, returning empty: {}", cause)
                emptyList()
            } else {
                throw e
            }
        }
    }
}
