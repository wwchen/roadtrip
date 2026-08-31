package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.domain.poi.PoiRow
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import ca.floo.roadtrip.service.routing.lineStringGeoJson
import ca.floo.roadtrip.support.RoutingException
import ca.floo.roadtrip.support.causeChain
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory

private const val TOPOLOGY_FAULT = "TopologyException"

private val poisOnRouteLog = LoggerFactory.getLogger("PoisOnRouteService")

internal class PoisOnRouteService(
    private val routeCache: RouteCache,
    private val routeCorridorService: RouteCorridorService,
    private val poiService: PoiReader,
    private val defaultCategories: List<String> = defaultPoiTypes,
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
        } catch (e: RoutingException) {
            emptyOnTopologyFault(e)
        } catch (e: DataAccessException) {
            emptyOnTopologyFault(e)
        }
    }

    /** A GEOS self-intersection on one corridor is a bad shape, not an outage: serve zero POIs. */
    private fun emptyOnTopologyFault(e: RuntimeException): List<PoiRow> {
        val chain = causeChain(e)
        if (!chain.contains(TOPOLOGY_FAULT)) throw e
        poisOnRouteLog.warn("on-route GEOS topology fault, returning empty: {}", chain)
        return emptyList()
    }
}
