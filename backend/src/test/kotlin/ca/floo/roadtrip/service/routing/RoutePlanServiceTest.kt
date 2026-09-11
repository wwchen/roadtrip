package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.client.mapbox.MapboxDirections
import ca.floo.roadtrip.model.routing.RouteLeg
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.support.RoutingException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val CORRIDOR_POLYGON_JSON = """{"type":"Polygon","coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]}"""
private const val CORRIDOR_UNAVAILABLE = "corridor_unavailable"
private const val RADIUS_MILES = 5.0
private val cacheTtl: Duration = Duration.ofMinutes(10)

private val vancouver = -123.1207 to 49.2827
private val calgary = -114.0719 to 51.0447

private val cachedRoute =
    RouteResponse(
        coordinates = listOf(listOf(-123.1207, 49.2827), listOf(-114.0719, 51.0447)),
        distanceMeters = 971_000.0,
        durationSeconds = 37_000.0,
        legs = listOf(RouteLeg(distanceMeters = 971_000.0, durationSeconds = 37_000.0)),
    )

class RoutePlanServiceTest {
    @Test
    fun `adjacent duplicate waypoints refuse before any upstream call`() {
        // The cache is empty, so a service that reached Mapbox would throw
        // RoutingException("roadtrip.mapbox.token not configured") instead.
        val result = runBlocking { service(corridor = refusingCorridor()).plan(listOf(vancouver, vancouver), null) }

        assertEquals(RoutePlanResult.DuplicateWaypoints(index = 1), result)
    }

    @Test
    fun `a route with no corridor requested carries no polygon`() {
        val result = runBlocking { service(corridor = refusingCorridor()).plan(listOf(vancouver, calgary), null) }

        val planned = assertIs<RoutePlanResult.Planned>(result)
        assertEquals(cachedRoute, planned.plan.directions)
        assertEquals(listOf(vancouver, calgary), planned.plan.waypoints)
        assertNull(planned.plan.corridorRadiusMiles)
        assertNull(planned.plan.corridorGeoJson)
    }

    @Test
    fun `a requested corridor is carried parsed, not as text`() {
        val result = runBlocking { service(corridor = polygonCorridor()).plan(listOf(vancouver, calgary), RADIUS_MILES) }

        val planned = assertIs<RoutePlanResult.Planned>(result)
        assertEquals(RADIUS_MILES, planned.plan.corridorRadiusMiles)
        assertEquals(
            "Polygon",
            planned.plan.corridorGeoJson!!
                .jsonObject["type"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `a corridor failure is its own outcome, not a routing failure`() {
        val result = runBlocking { service(corridor = refusingCorridor()).plan(listOf(vancouver, calgary), RADIUS_MILES) }

        assertEquals(RoutePlanResult.CorridorUnavailable(CORRIDOR_UNAVAILABLE), result)
    }

    @Test
    fun `an unreachable directions upstream is its own outcome`() {
        val emptyCache = RouteCache(directions = MapboxDirections(token = null), ttl = cacheTtl)

        val result = runBlocking { RoutePlanService(emptyCache, refusingCorridor()).plan(listOf(vancouver, calgary), null) }

        assertEquals(RoutePlanResult.DirectionsUnavailable("roadtrip.mapbox.token not configured"), result)
    }

    private fun service(corridor: RouteCorridorService): RoutePlanService {
        val cache = RouteCache(directions = MapboxDirections(token = null), ttl = cacheTtl)
        cache.put(listOf(vancouver, calgary), cachedRoute)
        return RoutePlanService(cache, corridor)
    }

    /** A corridor service that answers without PostGIS. */
    private fun polygonCorridor(): RouteCorridorService =
        object : RouteCorridorService(RouteCorridorRepo(DSL.using(SQLDialect.POSTGRES))) {
            override fun bufferedPolygonGeoJson(
                lineGeoJson: String,
                radiusMiles: Double,
            ): String = CORRIDOR_POLYGON_JSON
        }

    private fun refusingCorridor(): RouteCorridorService =
        object : RouteCorridorService(RouteCorridorRepo(DSL.using(SQLDialect.POSTGRES))) {
            override fun bufferedPolygonGeoJson(
                lineGeoJson: String,
                radiusMiles: Double,
            ): String = throw RoutingException(CORRIDOR_UNAVAILABLE)
        }
}
