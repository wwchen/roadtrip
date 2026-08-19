package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.routing.GeocodeResult
import ca.floo.roadtrip.route.api.geocode.geocodeResponseDto
import ca.floo.roadtrip.route.common.encodeApiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeocodeRoutesTest {
    @Test
    fun `geocode response serializes results with dto`() {
        val payload =
            encodeApiJson(
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
        // A place with no reported extent omits the key entirely rather than
        // shipping a null the client has to distinguish from an empty box.
        assertNull(result["bbox"])
    }

    @Test
    fun `a region serializes its extent as west south east north`() {
        val payload =
            encodeApiJson(
                geocodeResponseDto(
                    listOf(
                        GeocodeResult(
                            id = "region.1",
                            placeName = "Utah, United States",
                            placeType = "region",
                            lng = -111.0937,
                            lat = 39.3210,
                            bbox = Bbox(west = -114.052, south = 36.997, east = -109.041, north = 42.001),
                        ),
                    ),
                ),
            )
        val result =
            Json
                .parseToJsonElement(payload)
                .jsonObject["results"]!!
                .jsonArray
                .single()
                .jsonObject

        assertEquals(
            listOf(-114.052, 36.997, -109.041, 42.001),
            result["bbox"]!!.jsonArray.map { it.jsonPrimitive.double },
        )
    }
}
