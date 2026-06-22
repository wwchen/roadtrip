package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.reservecalifornia.CachedReserveCaliforniaAvailability
import ca.floo.roadtrip.clients.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.adapters.reservecalifornia.ReserveCaliforniaReservationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveCaliforniaReservationProviderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `catalog availability merges real HTTP grids across facilities and narrows to catalog rows`() =
        runBlocking {
            server.createContext("/rdr/search/grid") { exchange ->
                assertEquals("POST", exchange.requestMethod)
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val facilityId =
                    Json
                        .parseToJsonElement(body)
                        .jsonObject["FacilityId"]!!
                        .jsonPrimitive
                        .long
                when (facilityId) {
                    611L -> respondJson(exchange, gridJson(611, 43791, "Campsite #078", "2026-12-15"))
                    612L -> respondJson(exchange, gridJson(612, 43793, "Campsite #W079", "2026-12-16"))
                    else -> error("unexpected request body: $body")
                }
            }
            server.start()

            val client = HttpReserveCaliforniaAvailabilityClient(rdrBaseUrl = "$baseUrl/rdr")
            val provider = ReserveCaliforniaReservationProvider(CachedReserveCaliforniaAvailability(client))

            val batch =
                provider.catalogAvailability(
                    CatalogAvailabilityRequest(
                        ref = ProviderRef.ReserveCalifornia(placeId = 690, facilityIds = listOf(611, 612)),
                        reservables =
                            listOf(
                                CatalogReservableRef(rid = "site:reservecalifornia:43793", vendorId = "43793"),
                            ),
                        startDate = LocalDate.parse("2026-12-15"),
                        endDate = LocalDate.parse("2026-12-18"),
                    ),
                )

            assertEquals(ReservationProviderId.RESERVECALIFORNIA, provider.id)
            assertEquals(true, provider.capabilities.supportsAvailability)
            assertEquals(false, provider.capabilities.supportsAlerts)
            assertEquals(183, provider.capabilities.bookingHorizonDays)
            assertEquals("reservecalifornia", batch.provider)
            assertEquals("690", batch.campgroundId)
            assertEquals("611,612", batch.mapId)
            assertEquals(3, batch.observations.size)
            assertEquals(setOf("site:reservecalifornia:43793"), batch.observations.map { it.reservableId }.toSet())
            assertEquals(
                listOf(
                    AvailabilityStatus.UNKNOWN,
                    AvailabilityStatus.AVAILABLE,
                    AvailabilityStatus.UNKNOWN,
                ),
                batch.observations.sortedBy { it.date }.map { it.status },
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

    private fun gridJson(
        facilityId: Long,
        unitId: Long,
        unitName: String,
        availableDate: String,
    ): String =
        """
        {
          "Facility": {
            "FacilityId": $facilityId,
            "Name": "Facility $facilityId",
            "Units": {
              "bucket2.$unitId": {
                "UnitId": $unitId,
                "Name": "$unitName",
                "IsWebViewable": true,
                "AllowWebBooking": true,
                "Slices": {
                  "${availableDate}T00:00:00": {
                    "Date": "${availableDate}T00:00:00",
                    "IsFree": true,
                    "IsBlocked": false,
                    "IsWalkin": false,
                    "ReservationId": 0,
                    "Lock": null
                  }
                }
              }
            }
          }
        }
        """.trimIndent()
}
