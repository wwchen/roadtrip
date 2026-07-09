package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.clients.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaAvailabilityProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReserveCaliforniaAvailabilityProviderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}"
    private var serverStarted = false
    private var serverExecutor: ExecutorService? = null

    @AfterTest
    fun stopServer() {
        if (serverStarted) server.stop(0)
        serverExecutor?.shutdownNow()
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
            startServer()

            val client = HttpReserveCaliforniaAvailabilityClient(rdrBaseUrl = "$baseUrl/rdr")
            val provider = ReserveCaliforniaAvailabilityProvider(client)

            val batch =
                provider.catalogAvailability(
                    ref = ProviderRef.ReserveCalifornia(placeId = 690, facilityIds = listOf(611, 612)),
                    reservables =
                        listOf(
                            CatalogReservableRef(rid = "site:reservecalifornia:43793", vendorId = "43793"),
                        ),
                    startDate = LocalDate.parse("2026-12-15"),
                    endDate = LocalDate.parse("2026-12-18"),
                )

            assertEquals(AvailabilityProviderId.RESERVECALIFORNIA, provider.id)
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

    @Test
    fun `catalog availability fetches facility grids concurrently`() =
        runBlocking {
            val activeRequests = AtomicInteger(0)
            val maxActiveRequests = AtomicInteger(0)
            server.createContext("/rdr/search/grid") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val facilityId =
                    Json
                        .parseToJsonElement(body)
                        .jsonObject["FacilityId"]!!
                        .jsonPrimitive
                        .long
                val active = activeRequests.incrementAndGet()
                maxActiveRequests.updateAndGet { current -> maxOf(current, active) }
                try {
                    Thread.sleep(500)
                    respondJson(exchange, gridJson(facilityId, facilityId * 100, "Facility $facilityId Site", "2026-12-15"))
                } finally {
                    activeRequests.decrementAndGet()
                }
            }
            startServer(Executors.newFixedThreadPool(2))

            val client = HttpReserveCaliforniaAvailabilityClient(rdrBaseUrl = "$baseUrl/rdr")
            val provider = ReserveCaliforniaAvailabilityProvider(client)

            provider.catalogAvailability(
                ref = ProviderRef.ReserveCalifornia(placeId = 690, facilityIds = listOf(611, 612)),
                reservables = emptyList(),
                startDate = LocalDate.parse("2026-12-15"),
                endDate = LocalDate.parse("2026-12-16"),
            )

            assertTrue(maxActiveRequests.get() > 1, "facility grid requests should overlap")
        }

    @Test
    fun `catalog availability uses provider clock when no facility observations exist`() =
        runBlocking {
            val fixed = Instant.parse("2026-06-22T12:00:00Z")
            val client = HttpReserveCaliforniaAvailabilityClient(rdrBaseUrl = "$baseUrl/rdr")
            val provider =
                ReserveCaliforniaAvailabilityProvider(
                    client = client,
                    clock = Clock.fixed(fixed, ZoneOffset.UTC),
                )

            val batch =
                provider.catalogAvailability(
                    ref = ProviderRef.ReserveCalifornia(placeId = 690, facilityIds = emptyList()),
                    reservables =
                        listOf(
                            CatalogReservableRef(rid = "site:reservecalifornia:43793", vendorId = "43793"),
                        ),
                    startDate = LocalDate.parse("2026-12-15"),
                    endDate = LocalDate.parse("2026-12-17"),
                )

            assertEquals(listOf(fixed, fixed), batch.observations.map { it.observedAt })
            assertEquals(listOf(AvailabilityStatus.UNKNOWN, AvailabilityStatus.UNKNOWN), batch.observations.map { it.status })
        }

    private fun startServer(executor: ExecutorService? = null) {
        if (executor != null) {
            serverExecutor = executor
            server.executor = executor
        }
        server.start()
        serverStarted = true
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
