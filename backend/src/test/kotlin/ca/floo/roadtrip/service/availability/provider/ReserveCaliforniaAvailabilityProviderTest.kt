package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.model.domain.provider.BookingProvider
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OVERLAPPING_REQUESTS = 2

/** Generous ceiling: the latch releases as soon as both requests arrive, so
 *  this only bounds an actual regression rather than pacing the happy path. */
private const val OVERLAP_TIMEOUT_SECONDS = 10L

class ReserveCaliforniaAvailabilityProviderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val baseUrl = "http://127.0.0.1:${server.address.port}"
    private var serverStarted = false
    private var serverExecutorService: ExecutorService? = null

    @AfterTest
    fun stopServer() {
        if (serverStarted) server.stop(0)
        serverExecutorService?.shutdownNow()
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

            val availabilityClient = HttpReserveCaliforniaAvailabilityClient(rdrBaseUrl = "$baseUrl/rdr")
            val provider = ReserveCaliforniaAvailabilityProvider(availabilityClient = availabilityClient, enabled = true)

            val batch =
                provider.catalogAvailability(
                    campground = testCampground(bookingProvider = "reservecalifornia", bookingProviderRef = "690:611,612"),
                    campsites =
                        listOf(
                            campsiteFixture(id = 43793, vendor = "reservecalifornia", vendorId = "43793"),
                        ),
                    startDate = LocalDate.parse("2026-12-15"),
                    endDate = LocalDate.parse("2026-12-18"),
                )

            assertEquals(BookingProvider.RESERVECALIFORNIA, provider.id)
            assertEquals(false, provider.capabilities.supportsInternalPolling)
            assertEquals(183, provider.capabilities.bookingHorizonDays)
            assertEquals("reservecalifornia", batch.provider)
            assertEquals("690", batch.campgroundId)
            assertEquals("611,612", batch.mapId)
            assertEquals(3, batch.observations.size)
            assertEquals(setOf(43793L), batch.observations.map { it.campsiteId }.toSet())
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
            // Each request parks until OVERLAPPING_REQUESTS of them are in
            // flight at once, so the assertion is a fact the stub observed
            // rather than a race two sleeps happened to win.
            val bothInFlight = CountDownLatch(OVERLAPPING_REQUESTS)
            val observedOverlap = AtomicBoolean(false)
            server.createContext("/rdr/search/grid") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val facilityId =
                    Json
                        .parseToJsonElement(body)
                        .jsonObject["FacilityId"]!!
                        .jsonPrimitive
                        .long
                bothInFlight.countDown()
                if (bothInFlight.await(OVERLAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    observedOverlap.set(true)
                }
                respondJson(exchange, gridJson(facilityId, facilityId * 100, "Facility $facilityId Site", "2026-12-15"))
            }
            startServer(Executors.newFixedThreadPool(OVERLAPPING_REQUESTS))

            val availabilityClient = HttpReserveCaliforniaAvailabilityClient(rdrBaseUrl = "$baseUrl/rdr")
            val provider = ReserveCaliforniaAvailabilityProvider(availabilityClient = availabilityClient, enabled = true)

            provider.catalogAvailability(
                campground = testCampground(bookingProvider = "reservecalifornia", bookingProviderRef = "690:611,612"),
                campsites = emptyList(),
                startDate = LocalDate.parse("2026-12-15"),
                endDate = LocalDate.parse("2026-12-16"),
            )

            assertTrue(observedOverlap.get(), "facility grid requests should overlap")
        }

    @Test
    fun `catalog availability uses provider clock when no facility observations exist`() =
        runBlocking {
            val fixed = Instant.parse("2026-06-22T12:00:00Z")
            val availabilityClient =
                object : ReserveCaliforniaAvailabilityClient {
                    override suspend fun fetchGrid(
                        facilityId: Long,
                        startDate: LocalDate,
                        endDate: LocalDate,
                        minDate: LocalDate,
                        maxDate: LocalDate,
                    ): ReserveCaliforniaGridAvailability =
                        ReserveCaliforniaGridAvailability(
                            facilityId = facilityId,
                            observedAt = Instant.EPOCH,
                            statuses = emptyMap(),
                        )
                }
            val provider =
                ReserveCaliforniaAvailabilityProvider(
                    availabilityClient = availabilityClient,
                    enabled = true,
                    clock = Clock.fixed(fixed, ZoneOffset.UTC),
                )

            val batch =
                provider.catalogAvailability(
                    campground = testCampground(bookingProvider = "reservecalifornia", bookingProviderRef = "690:611"),
                    campsites =
                        listOf(
                            campsiteFixture(id = 43793, vendor = "reservecalifornia", vendorId = "43793"),
                        ),
                    startDate = LocalDate.parse("2026-12-15"),
                    endDate = LocalDate.parse("2026-12-17"),
                )

            assertEquals(listOf(fixed, fixed), batch.observations.map { it.observedAt })
            assertEquals(listOf(AvailabilityStatus.UNKNOWN, AvailabilityStatus.UNKNOWN), batch.observations.map { it.status })
        }

    private fun startServer(executor: ExecutorService? = null) {
        if (executor != null) {
            serverExecutorService = executor
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
