package ca.floo.roadtrip.route

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.route.api.geocode.geocodeRoutes
import ca.floo.roadtrip.service.geocode.GeocodeService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OVER_MAX_QUERY_LENGTH = 201
private const val VANCOUVER_FEATURE =
    """{"features":[{"id":"place.1","place_name":"Vancouver, British Columbia, Canada",""" +
        """"place_type":["place"],"center":[-123.1207,49.2827]}]}"""

class GeocodeRoutesTest {
    @Test
    fun `a found place answers 200 with the results envelope`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(okService()) } }

            val resp = client.get("/api/geocode?q=Vancouver")

            assertEquals(HttpStatusCode.OK, resp.status)
            val results = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["results"]!!.jsonArray
            assertEquals(
                "place.1",
                results
                    .single()
                    .jsonObject["id"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `an unset token answers 503 geocoding_unavailable`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(unconfiguredService()) } }

            val resp = client.get("/api/geocode?q=Vancouver")

            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals("geocoding_unavailable", errorOf(resp.bodyAsText()))
        }

    @Test
    fun `a blank query answers 400 bad_query`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(okService()) } }

            val resp = client.get("/api/geocode?q=")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_query", errorOf(resp.bodyAsText()))
        }

    @Test
    fun `an over-long query answers 400 bad_query`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(okService()) } }

            val resp = client.get("/api/geocode?q=${"x".repeat(OVER_MAX_QUERY_LENGTH)}")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_query", errorOf(resp.bodyAsText()))
        }

    @Test
    fun `an upstream failure answers 503 geocoding_unavailable`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(failingService()) } }

            val resp = client.get("/api/geocode?q=Vancouver")

            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals("geocoding_unavailable", errorOf(resp.bodyAsText()))
        }

    private fun errorOf(body: String): String =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonPrimitive.content

    private fun okService(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient =
                    HttpClient(
                        MockEngine {
                            respond(VANCOUVER_FEATURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ),
            ),
        )

    private fun failingService(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }),
            ),
        )

    private fun unconfiguredService(): GeocodeService = GeocodeService(MapboxGeocoder(token = null))
}
