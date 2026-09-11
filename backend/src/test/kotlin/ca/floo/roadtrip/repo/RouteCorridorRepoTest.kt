package ca.floo.roadtrip.repo

import ca.floo.roadtrip.support.CorridorUnavailableException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val VANCOUVER_TO_SEATTLE =
    """{"type":"LineString","coordinates":[[-123.1207,49.2827],[-122.3321,47.6062]]}"""
private const val NOT_GEOJSON = "definitely not geojson"
private const val TEN_MILES_IN_METERS = 16_093.4

class RouteCorridorRepoTest : SharedDbTest() {
    private val repo by lazy { RouteCorridorRepo(ctx) }

    @Test
    fun `buffering a line returns a polygon`() {
        val polygon = repo.bufferedPolygonGeoJson(VANCOUVER_TO_SEATTLE, TEN_MILES_IN_METERS)

        assertTrue(polygon.contains("\"type\":\"Polygon\"") || polygon.contains("\"type\": \"Polygon\""))
    }

    @Test
    fun `a geometry PostGIS cannot read surfaces as a domain failure, never as jOOQ's`() {
        val failure =
            assertFailsWith<CorridorUnavailableException> {
                repo.bufferedPolygonGeoJson(NOT_GEOJSON, TEN_MILES_IN_METERS)
            }

        assertEquals("route corridor query failed", failure.message)
    }
}
