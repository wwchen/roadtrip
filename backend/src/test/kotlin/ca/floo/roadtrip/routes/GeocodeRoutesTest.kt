package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.GeocodeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class GeocodeRoutesTest {
    @Test
    fun `geocode response serializes results with dto`() {
        val payload =
            encodeGeocodeJson(
                geocodeResponseDto(
                    listOf(
                        GeocodeResult(
                            id = "place.1",
                            placeName = "Vancouver, British Columbia, Canada",
                            placeType = "place",
                            lng = -123.1207,
                            lat = 49.2827,
                        ),
                    ),
                ),
            )
        val json = Json.parseToJsonElement(payload).jsonObject

        val result = json["results"]!!.jsonArray.single().jsonObject
        assertEquals("place.1", result["id"]!!.jsonPrimitive.content)
        assertEquals("Vancouver, British Columbia, Canada", result["place_name"]!!.jsonPrimitive.content)
        assertEquals("place", result["place_type"]!!.jsonPrimitive.content)
        assertEquals(-123.1207, result["lng"]!!.jsonPrimitive.double)
        assertEquals(49.2827, result["lat"]!!.jsonPrimitive.double)
    }
}
