package ca.floo.roadtrip.client.recgov

import ca.floo.roadtrip.support.RecGovException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP-layer coverage for the one client with call shaping of its own. The
 * 429 case is the load-bearing one: it used to sleep 3s/6s/12s inside this
 * client and then throw an untyped RuntimeException, which meant the failover
 * fetcher never saw a rate limit it could cool the provider down for.
 */
class HttpRecgovAvailabilityClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}/api/camps/availability/campground"
    private val requests = AtomicInteger(0)

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `parses a month payload from a real HTTP response`() =
        runBlocking {
            respond { exchange ->
                assertEquals("GET", exchange.requestMethod)
                assertEquals("/api/camps/availability/campground/232447/month", exchange.requestURI.path)
                assertEquals(
                    "start_date=2026-12-01T00%3A00%3A00.000Z",
                    exchange.requestURI.rawQuery,
                )
                json(exchange, HTTP_OK, monthJson())
            }

            val campsites = client().fetchMonth("232447", "2026-12-01")

            assertEquals(setOf("100", "101"), campsites.keys)
            assertEquals("A1", campsites["100"]?.site)
            assertEquals("Loop A", campsites["100"]?.loop)
            assertEquals(4, campsites["100"]?.maxNumPeople)
            assertEquals(listOf("TENT", "RV"), campsites["100"]?.equipmentTypes)
            assertEquals(
                mapOf(
                    "2026-12-01T00:00:00Z" to "Available",
                    "2026-12-02T00:00:00Z" to "Reserved",
                ),
                campsites["100"]?.availabilities,
            )
            assertEquals(1, requests.get())
        }

    @Test
    fun `a 429 is surfaced immediately as a rate-limited vendor error`() =
        runBlocking {
            respond { exchange -> json(exchange, HTTP_TOO_MANY_REQUESTS, """{"error":"slow down"}""") }

            val thrown =
                runCatching { client().fetchMonth("232447", "2026-12-01") }.exceptionOrNull()

            assertTrue(thrown is RecGovException, "expected RecGovException, got $thrown")
            assertEquals(HTTP_TOO_MANY_REQUESTS, thrown.httpStatus)
            assertEquals(1, requests.get(), "429 must not be retried inside the client")
        }

    @Test
    fun `a 5xx is surfaced as a vendor error carrying the upstream status`() =
        runBlocking {
            respond { exchange -> json(exchange, HTTP_BAD_GATEWAY, "upstream exploded") }

            val thrown =
                runCatching { client().fetchMonth("232447", "2026-12-01") }.exceptionOrNull()

            assertTrue(thrown is RecGovException, "expected RecGovException, got $thrown")
            assertEquals(HTTP_BAD_GATEWAY, thrown.httpStatus)
            assertTrue(
                thrown.message!!.contains("upstream exploded"),
                "error body excerpt should identify the failure: ${thrown.message}",
            )
            // A 5xx is a server error, so Ktor's transport retry ladder gets it;
            // a 429 is a client error and does not, which is what keeps the rate
            // limit fast to surface.
            assertEquals(SERVER_ERROR_ATTEMPTS, requests.get())
        }

    private fun client(): HttpRecgovAvailabilityClient = HttpRecgovAvailabilityClient(minGapMs = 0L, availBaseUrl = baseUrl)

    private fun respond(handler: (HttpExchange) -> Unit) {
        server.createContext("/api/camps/availability/campground") { exchange ->
            requests.incrementAndGet()
            handler(exchange)
        }
        server.start()
    }

    private fun json(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun monthJson(): String =
        """
        {
          "campsites": {
            "100": {
              "campsite_id": "100",
              "site": "A1",
              "loop": "Loop A",
              "campsite_type": "STANDARD",
              "max_num_people": 4,
              "equipment_types": ["TENT", "RV"],
              "availabilities": {
                "2026-12-01T00:00:00Z": "Available",
                "2026-12-02T00:00:00Z": "Reserved"
              }
            },
            "101": {
              "campsite_id": "101",
              "site": "A2",
              "loop": "Loop A",
              "campsite_type": "STANDARD",
              "max_num_people": 6,
              "equipment_types": [],
              "availabilities": {
                "2026-12-01T00:00:00Z": "Not Available"
              }
            }
          }
        }
        """.trimIndent()

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_BAD_GATEWAY = 502

        /** One call plus the client's two transport retries. */
        const val SERVER_ERROR_ATTEMPTS = 3
    }
}
