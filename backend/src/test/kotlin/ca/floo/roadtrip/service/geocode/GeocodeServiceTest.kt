package ca.floo.roadtrip.service.geocode

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.route.common.encodeApiJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OVER_MAX_QUERY_LENGTH = 201
private const val VANCOUVER_FEATURE =
    """{"features":[{"id":"place.1","place_name":"Vancouver, British Columbia, Canada",""" +
        """"place_type":["place"],"center":[-123.1207,49.2827]}]}"""
private const val UTAH_FEATURE =
    """{"features":[{"id":"region.1","place_name":"Utah, United States","place_type":["region"],""" +
        """"center":[-111.0937,39.3210],"bbox":[-114.052,36.997,-109.041,42.001]}]}"""

class GeocodeServiceTest {
    private val requests = mutableListOf<HttpRequestData>()

    @Test
    fun `an unset token refuses before any upstream call`() {
        val outcome =
            runBlocking { serviceWithoutToken().geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        assertEquals(GeocodeOutcome.NotConfigured, outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a blank query is refused`() {
        val outcome = runBlocking { service(VANCOUVER_FEATURE).geocode("   ", autocomplete = true, proximity = null, limit = null) }

        assertEquals(GeocodeOutcome.BadQuery, outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a query over the length cap is refused`() {
        val outcome =
            runBlocking {
                service(VANCOUVER_FEATURE).geocode("x".repeat(OVER_MAX_QUERY_LENGTH), autocomplete = true, proximity = null, limit = null)
            }

        assertEquals(GeocodeOutcome.BadQuery, outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `limit is clamped into range, and an absent one takes the default`() {
        val geocoder = service(VANCOUVER_FEATURE)

        runBlocking { geocoder.geocode("Vancouver", autocomplete = true, proximity = null, limit = 99) }
        runBlocking { geocoder.geocode("Vancouver", autocomplete = true, proximity = null, limit = 0) }
        runBlocking { geocoder.geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        assertEquals(listOf("10", "1", "5"), requests.map { queryParamOf(it, "limit") })
    }

    @Test
    fun `a well-formed proximity is forwarded and a malformed one is dropped`() {
        val geocoder = service(VANCOUVER_FEATURE)

        runBlocking { geocoder.geocode("Dallas", autocomplete = true, proximity = "-96.797,32.777", limit = null) }
        runBlocking { geocoder.geocode("Dallas", autocomplete = true, proximity = "not-a-point", limit = null) }

        assertEquals("-96.797,32.777", queryParamOf(requests[0], "proximity"))
        assertNull(queryParamOf(requests[1], "proximity"))
    }

    @Test
    fun `autocomplete off is forwarded as false`() {
        runBlocking { service(VANCOUVER_FEATURE).geocode("Vancouver", autocomplete = false, proximity = null, limit = null) }

        assertEquals("false", queryParamOf(requests.single(), "autocomplete"))
    }

    @Test
    fun `an upstream failure is its own outcome`() {
        val outcome =
            runBlocking { failingService().geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        assertEquals(GeocodeOutcome.Unavailable, outcome)
    }

    @Test
    fun `a found place serializes with the dto`() {
        val outcome = runBlocking { service(VANCOUVER_FEATURE).geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        val result = resultsOf(outcome).single().jsonObject
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
        val outcome = runBlocking { service(UTAH_FEATURE).geocode("Utah", autocomplete = true, proximity = null, limit = null) }

        assertEquals(
            listOf(-114.052, 36.997, -109.041, 42.001),
            resultsOf(outcome)
                .single()
                .jsonObject["bbox"]!!
                .jsonArray
                .map { it.jsonPrimitive.double },
        )
    }

    private fun resultsOf(outcome: GeocodeOutcome) =
        Json
            .parseToJsonElement(encodeApiJson(assertIs<GeocodeOutcome.Found>(outcome).response))
            .jsonObject["results"]!!
            .jsonArray

    private fun queryParamOf(
        request: HttpRequestData,
        name: String,
    ): String? = request.url.parameters[name]

    private fun service(body: String): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            requests += request
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ),
            ),
        )

    private fun failingService(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            requests += request
                            respondError(HttpStatusCode.InternalServerError)
                        },
                    ),
            ),
        )

    private fun serviceWithoutToken(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = null,
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            requests += request
                            respondError(HttpStatusCode.InternalServerError)
                        },
                    ),
            ),
        )
}
