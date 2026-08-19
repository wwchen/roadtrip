package ca.floo.roadtrip.client.mapbox

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The extent half of the geocoder: a region is an area, and the upstream already
 * says how big it is. These pin that we read it, and that we refuse a broken one
 * rather than framing the map on it.
 */
class MapboxGeocoderTest {
    private fun geocoderReturning(body: String): MapboxGeocoder {
        val engine =
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return MapboxGeocoder(token = "pk.test", httpClient = HttpClient(engine))
    }

    private fun feature(
        placeType: String,
        bbox: String?,
    ): String =
        """
        {"features":[{
          "id":"region.1",
          "place_name":"Utah, United States",
          "place_type":["$placeType"],
          "center":[-111.0937,39.3210]
          ${bbox?.let { ""","bbox":$it""" }.orEmpty()}
        }]}
        """.trimIndent()

    @Test
    fun `a region carries the extent the upstream reported`() {
        val geocoder = geocoderReturning(feature("region", "[-114.052,36.997,-109.041,42.001]"))

        val result = runBlocking { geocoder.forward("Utah") }.single()

        val bbox = requireNotNull(result.bbox)
        assertEquals(-114.052, bbox.west)
        assertEquals(36.997, bbox.south)
        assertEquals(-109.041, bbox.east)
        assertEquals(42.001, bbox.north)
    }

    @Test
    fun `an address with no extent keeps a null bbox`() {
        val geocoder = geocoderReturning(feature("address", null))

        assertNull(runBlocking { geocoder.forward("1 Main St") }.single().bbox)
    }

    @Test
    fun `a short bbox is dropped rather than half-read`() {
        val geocoder = geocoderReturning(feature("region", "[-114.052,36.997,-109.041]"))

        assertNull(runBlocking { geocoder.forward("Utah") }.single().bbox)
    }

    @Test
    fun `an inverted bbox is dropped`() {
        val geocoder = geocoderReturning(feature("region", "[-109.041,42.001,-114.052,36.997]"))

        assertNull(runBlocking { geocoder.forward("Utah") }.single().bbox)
    }

    @Test
    fun `a non-numeric bbox entry is dropped`() {
        val geocoder = geocoderReturning(feature("region", """[-114.052,"not-a-number",-109.041,42.001]"""))

        assertNull(runBlocking { geocoder.forward("Utah") }.single().bbox)
    }
}
