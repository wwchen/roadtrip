package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CampflareAvailabilityClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}/v2"

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `fetches bulk availability through real local HTTP and classifies statuses`() =
        runBlocking {
            server.createContext("/v2/campgrounds/availability") { exchange ->
                assertEquals("POST", exchange.requestMethod)
                assertEquals("test-key", exchange.requestHeaders.getFirst("Authorization"))
                assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
                val body =
                    Json
                        .parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() })
                        .jsonObject
                assertEquals(
                    "upper-pines-campground-447",
                    body["campground_ids"]!!
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .contentOrNull,
                )
                assertEquals("2026-06-01", body["start_date"]!!.jsonPrimitive.contentOrNull)
                assertEquals("2026-06-07", body["end_date"]!!.jsonPrimitive.contentOrNull)
                respondJson(exchange, bulkJson())
            }
            server.start()

            val client = HttpCampflareAvailabilityClient(apiBaseUrl = baseUrl, apiKey = "test-key")
            val availability =
                client.fetchAvailability(
                    campgroundIds = listOf("upper-pines-campground-447"),
                    startDate = LocalDate.parse("2026-06-01"),
                    endDate = LocalDate.parse("2026-06-07"),
                )

            assertEquals(setOf("upper-pines-campground-447"), availability.campgrounds.keys)
            val campsite = availability.campgrounds["upper-pines-campground-447"]!!.campsiteAvailability.single()
            assertEquals("upper-pines-site-100", campsite.campsiteId)
            assertEquals(
                mapOf(
                    LocalDate.parse("2026-06-01") to AvailabilityStatus.AVAILABLE,
                    LocalDate.parse("2026-06-02") to AvailabilityStatus.RESERVED,
                    LocalDate.parse("2026-06-03") to AvailabilityStatus.CLOSED,
                    LocalDate.parse("2026-06-04") to AvailabilityStatus.FIRST_COME,
                    LocalDate.parse("2026-06-05") to AvailabilityStatus.UNKNOWN,
                    LocalDate.parse("2026-06-06") to AvailabilityStatus.UNKNOWN,
                ),
                campsite.availability,
            )
        }

    private fun respondJson(
        exchange: HttpExchange,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun bulkJson(): String =
        """
        {
          "campgrounds": [
            {
              "campground_id": "upper-pines-campground-447",
              "campsite_availability": [
                {
                  "campsite_id": "upper-pines-site-100",
                  "availability": {
                    "2026-06-01": "available",
                    "2026-06-02": "reserved",
                    "2026-06-03": "closed",
                    "2026-06-04": "first-come-first-serve",
                    "2026-06-05": "not-yet-released",
                    "2026-06-06": "unknown"
                  }
                }
              ]
            }
          ]
        }
        """.trimIndent()
}
