package ca.floo.roadtrip.service.etl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoJsonTest {
    @Test
    fun `point geojson serializes as point geometry dto`() {
        val json = Json.parseToJsonElement(pointGeoJson(lng = -123.1, lat = 49.2)).jsonObject

        assertEquals("Point", json["type"]!!.jsonPrimitive.content)
        val coordinates = json["coordinates"]!!.jsonArray
        assertEquals(-123.1, coordinates[0].jsonPrimitive.double)
        assertEquals(49.2, coordinates[1].jsonPrimitive.double)
    }
}
