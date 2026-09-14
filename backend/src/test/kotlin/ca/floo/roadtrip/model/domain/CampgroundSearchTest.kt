package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun coordinates(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

class CampgroundSearchTest {
    @Test
    fun `a Polygon's bbox area is width times height`() {
        val polygon = coordinates("""[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]""")
        assertEquals(0.8 * 0.7, boundaryBboxAreaSqDeg(polygon)!!, 1e-9)
    }

    @Test
    fun `a MultiPolygon's bbox area spans every ring`() {
        val ringA = """[[[-120.0,38.0],[-119.0,38.0],[-119.0,39.0],[-120.0,39.0],[-120.0,38.0]]]"""
        val ringB = """[[[10.0,10.0],[12.0,10.0],[12.0,11.0],[10.0,11.0],[10.0,10.0]]]"""
        val multiPolygon = coordinates("[$ringA,$ringB]")
        val area = boundaryBboxAreaSqDeg(multiPolygon)!!
        assertEquals((12.0 - -120.0) * (39.0 - 10.0), area, 1e-9)
    }

    @Test
    fun `an empty array has no positions`() {
        assertNull(boundaryBboxAreaSqDeg(coordinates("[]")))
    }

    @Test
    fun `an array with no nested position arrays has no positions`() {
        assertNull(boundaryBboxAreaSqDeg(coordinates("[1,2,3]")))
    }
}
