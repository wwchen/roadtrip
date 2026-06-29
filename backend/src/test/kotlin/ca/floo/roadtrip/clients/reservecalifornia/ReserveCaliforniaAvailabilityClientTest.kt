package ca.floo.roadtrip.clients.reservecalifornia

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveCaliforniaAvailabilityClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `fetches grid through real local HTTP and classifies slices`() =
        runBlocking {
            server.createContext("/rdr/search/grid") { exchange ->
                assertEquals("POST", exchange.requestMethod)
                assertEquals("cali", exchange.requestHeaders.getFirst("tenantId"))
                val body =
                    Json
                        .parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() })
                        .jsonObject
                assertEquals(612L, body["FacilityId"]!!.jsonPrimitive.long)
                assertEquals("availability", body["UnitSort"]!!.jsonPrimitive.contentOrNull)
                assertEquals("2026-12-15", body["StartDate"]!!.jsonPrimitive.contentOrNull)
                assertEquals("2026-12-19", body["EndDate"]!!.jsonPrimitive.contentOrNull)
                assertEquals("2026-06-22T00:00:00", body["MinDate"]!!.jsonPrimitive.contentOrNull)
                assertEquals("2026-12-22T00:00:00", body["MaxDate"]!!.jsonPrimitive.contentOrNull)
                assertEquals(0, body["UnitTypesGroupIds"]!!.jsonArray.size)
                assertEquals(0, body["AmenityIds"]!!.jsonArray.size)
                respondJson(exchange, gridJson())
            }
            server.start()

            val client = HttpReserveCaliforniaAvailabilityClient(rdrBaseUrl = "$baseUrl/rdr")
            val grid =
                client.fetchGrid(
                    facilityId = 612,
                    startDate = LocalDate.parse("2026-12-15"),
                    endDate = LocalDate.parse("2026-12-19"),
                    minDate = LocalDate.parse("2026-06-22"),
                    maxDate = LocalDate.parse("2026-12-22"),
                )

            assertEquals(612L, grid.facilityId)
            assertEquals(setOf("43793", "43794"), grid.statuses.keys)
            assertEquals(
                mapOf(
                    LocalDate.parse("2026-12-15") to AvailabilityStatus.AVAILABLE,
                    LocalDate.parse("2026-12-16") to AvailabilityStatus.FIRST_COME,
                    LocalDate.parse("2026-12-17") to AvailabilityStatus.CLOSED,
                    LocalDate.parse("2026-12-18") to AvailabilityStatus.RESERVED,
                ),
                grid.statuses["43793"],
            )
            assertEquals(
                mapOf(
                    LocalDate.parse("2026-12-15") to AvailabilityStatus.CLOSED,
                    LocalDate.parse("2026-12-16") to AvailabilityStatus.CLOSED,
                ),
                grid.statuses["43794"],
                "unit-level booking flags must prevent free slices from becoming bookable",
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

    private fun gridJson(): String =
        """
        {
          "Facility": {
            "FacilityId": 612,
            "Name": "Weyland Camp (sites 79-130)",
            "Units": {
              "bucket2.43793": {
                "UnitId": 43793,
                "Name": "Campsite #W079",
                "IsWebViewable": true,
                "AllowWebBooking": true,
                "Slices": {
                  "2026-12-15T00:00:00": {
                    "Date": "2026-12-15T00:00:00",
                    "IsFree": true,
                    "IsBlocked": false,
                    "IsWalkin": false,
                    "ReservationId": 0,
                    "Lock": null
                  },
                  "2026-12-16T00:00:00": {
                    "Date": "2026-12-16T00:00:00",
                    "IsFree": true,
                    "IsBlocked": false,
                    "IsWalkin": true,
                    "ReservationId": 0,
                    "Lock": null
                  },
                  "2026-12-17T00:00:00": {
                    "Date": "2026-12-17T00:00:00",
                    "IsFree": false,
                    "IsBlocked": true,
                    "IsWalkin": false,
                    "ReservationId": 0,
                    "Lock": null
                  },
                  "2026-12-18T00:00:00": {
                    "Date": "2026-12-18T00:00:00",
                    "IsFree": false,
                    "IsBlocked": false,
                    "IsWalkin": false,
                    "ReservationId": 100,
                    "Lock": null
                  }
                }
              },
              "bucket2.43794": {
                "UnitId": 43794,
                "Name": "Campsite #W080",
                "IsWebViewable": true,
                "AllowWebBooking": false,
                "Slices": {
                  "2026-12-15T00:00:00": {
                    "Date": "2026-12-15T00:00:00",
                    "IsFree": true,
                    "IsBlocked": false,
                    "IsWalkin": false,
                    "ReservationId": 0,
                    "Lock": null
                  },
                  "2026-12-16T00:00:00": {
                    "Date": "2026-12-16T00:00:00",
                    "IsFree": true,
                    "IsBlocked": false,
                    "IsWalkin": false,
                    "ReservationId": 0,
                    "Lock": {"Id": 1}
                  }
                }
              }
            }
          }
        }
        """.trimIndent()
}
