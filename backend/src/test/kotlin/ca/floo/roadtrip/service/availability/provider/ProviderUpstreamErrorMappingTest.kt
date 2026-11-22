package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.AvailabilityPayloadException
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.support.AspiraException
import ca.floo.roadtrip.support.CampflareException
import ca.floo.roadtrip.support.ReserveAmericaException
import ca.floo.roadtrip.support.ReserveCaliforniaException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketException
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins each adapter's HTTP-status → [AvailabilityProviderError] classification.
 * The four adapters hand-rolled the same `when` with small, deliberate
 * differences (only Aspira and Campflare recognise a "blocked" status at all,
 * and only Aspira treats a WAF-shaped message as blocked); these cases exist so
 * the shared mapper cannot quietly change any of them.
 *
 * Classification matters operationally: `RateLimited` / `UpstreamUnavailable` /
 * `UpstreamBlocked` are the retryable outcomes that drive failover and provider
 * cooldown, so a mis-classified 401 would keep hammering a vendor.
 */
class ProviderUpstreamErrorMappingTest {
    private val start = LocalDate.parse("2026-06-01")
    private val end = LocalDate.parse("2026-06-03")

    @Test
    fun `aspira classification`() {
        assertAspira(429, AvailabilityProviderError.RateLimited::class)
        assertAspira(401, AvailabilityProviderError.UpstreamBlocked::class)
        assertAspira(403, AvailabilityProviderError.UpstreamBlocked::class)
        assertAspira(503, AvailabilityProviderError.UpstreamBlocked::class)
        assertAspira(500, AvailabilityProviderError.UpstreamUnavailable::class)
        assertAspira(404, AvailabilityProviderError.UpstreamUnavailable::class)
        assertAspira(null, AvailabilityProviderError.UpstreamUnavailable::class)
        // WAF challenges arrive with a non-blocking status but a telltale message.
        assertAspira(200, AvailabilityProviderError.UpstreamBlocked::class, message = "aspira WAF challenge")
    }

    @Test
    fun `campflare classification`() {
        assertCampflare(429, AvailabilityProviderError.RateLimited::class)
        assertCampflare(401, AvailabilityProviderError.UpstreamBlocked::class)
        assertCampflare(403, AvailabilityProviderError.UpstreamBlocked::class)
        // Campflare's 503 is an outage, not a block — unlike Aspira's.
        assertCampflare(503, AvailabilityProviderError.UpstreamUnavailable::class)
        assertCampflare(500, AvailabilityProviderError.UpstreamUnavailable::class)
        assertCampflare(404, AvailabilityProviderError.UpstreamUnavailable::class)
        assertCampflare(null, AvailabilityProviderError.UpstreamUnavailable::class)
        assertCampflare(200, AvailabilityProviderError.UpstreamUnavailable::class, message = "campflare WAF")
    }

    @Test
    fun `reserveamerica classification`() {
        assertReserveAmerica(429, AvailabilityProviderError.RateLimited::class)
        // ReserveAmerica recognises no blocked status: everything but 429 is an outage.
        assertReserveAmerica(401, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveAmerica(403, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveAmerica(503, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveAmerica(500, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveAmerica(null, AvailabilityProviderError.UpstreamUnavailable::class)
    }

    @Test
    fun `reservecalifornia classification`() {
        assertReserveCalifornia(429, AvailabilityProviderError.RateLimited::class)
        assertReserveCalifornia(401, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveCalifornia(403, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveCalifornia(503, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveCalifornia(500, AvailabilityProviderError.UpstreamUnavailable::class)
        assertReserveCalifornia(null, AvailabilityProviderError.UpstreamUnavailable::class)
    }

    @Test
    fun `a payload we could not read is unreadable_response, not the vendor's 5xx`() {
        val thrown = campflareFailure(AvailabilityPayloadException("campflare", "missing campgrounds array"))

        assertEquals("unreadable_response", thrown.code)
        assertTrue(thrown.message!!.contains(AvailabilityPayloadException::class.java.name), thrown.message)
        assertTrue(thrown.message!!.contains("missing campgrounds array"), thrown.message)
    }

    @Test
    fun `a body that is not JSON is unreadable_response`() {
        val notJson = runCatching { Json.parseToJsonElement("{\"campgrounds\": [") }.exceptionOrNull() as Exception

        assertEquals("unreadable_response", campflareFailure(notJson).code)
    }

    @Test
    fun `our own failure keeps its class and message instead of becoming upstream_5xx`() {
        val thrown = campflareFailure(IllegalStateException("campsite index out of sync"))

        assertEquals(AvailabilityProviderError.Unknown.CODE, thrown.code)
        assertEquals("java.lang.IllegalStateException: campsite index out of sync", thrown.message)
    }

    @Test
    fun `an unwrapped transport failure is still the vendor exchange failing`() {
        assertEquals("upstream_unreachable", campflareFailure(ConnectException("Connection refused")).code)
        assertEquals("upstream_5xx", campflareFailure(SocketException("Connection reset")).code)
    }

    private fun campflareFailure(cause: Exception): AvailabilityProviderError =
        runBlocking {
            val adapter =
                CampflareAvailabilityProvider(
                    CampflareAvailabilityClient { _, _, _ -> throw cause },
                    enabled = true,
                    configured = true,
                )
            assertFailsWith<AvailabilityProviderError> {
                adapter.availability(
                    campground =
                        testCampground(
                            bookingProvider = "campflare",
                            bookingProviderRef = "upper-pines-campground-447",
                            dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                        ),
                    campsites = listOf(errorMappingCampsite),
                    startDate = start,
                    endDate = end,
                )
            }
        }

    private fun assertAspira(
        httpStatus: Int?,
        expected: KClass<out AvailabilityProviderError>,
        message: String = "aspira HTTP $httpStatus",
    ) = runBlocking {
        val adapter =
            AspiraAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                availabilityClient =
                    object : AspiraAvailabilityClient {
                        override suspend fun fetch(
                            host: String,
                            mapId: Int,
                            startDate: LocalDate,
                            endDate: LocalDate,
                        ): AspiraAvailability = throw AspiraException(message, httpStatus = httpStatus)

                        override suspend fun fetchOccupancy(
                            host: String,
                            resourceLocationId: Int,
                            startDate: LocalDate,
                            endDate: LocalDate,
                        ): AspiraOccupancy = error("not used")
                    },
                enabled = true,
            )
        assertThrown(expected) {
            adapter.availability(
                campground = testCampground(bookingProvider = "aspira", bookingProviderRef = "pc:-2147483630:-2147483615:-2147483624"),
                campsites = listOf(errorMappingCampsite),
                startDate = start,
                endDate = end,
            )
        }
    }

    private fun assertCampflare(
        httpStatus: Int?,
        expected: KClass<out AvailabilityProviderError>,
        message: String = "campflare HTTP $httpStatus",
    ) = runBlocking {
        val adapter =
            CampflareAvailabilityProvider(
                CampflareAvailabilityClient { _, _, _ -> throw CampflareException(message, httpStatus = httpStatus) },
                enabled = true,
                configured = true,
            )
        assertThrown(expected) {
            adapter.availability(
                campground =
                    testCampground(
                        bookingProvider = "campflare",
                        bookingProviderRef = "upper-pines-campground-447",
                        dataProviderRef = DataProviderRef.Campflare(id = "upper-pines-campground-447"),
                    ),
                campsites = listOf(errorMappingCampsite),
                startDate = start,
                endDate = end,
            )
        }
    }

    private fun assertReserveAmerica(
        httpStatus: Int?,
        expected: KClass<out AvailabilityProviderError>,
    ) = runBlocking {
        val adapter =
            ReserveAmericaAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA),
                availabilityClient = { _, _, _, _, _ ->
                    throw ReserveAmericaException("reserveamerica HTTP $httpStatus", httpStatus = httpStatus)
                },
                enabled = true,
            )
        assertThrown(expected) {
            adapter.availability(
                campground = testCampground(bookingProvider = "reserveamerica", bookingProviderRef = "NY:489"),
                campsites = listOf(errorMappingCampsite),
                startDate = start,
                endDate = end,
            )
        }
    }

    private fun assertReserveCalifornia(
        httpStatus: Int?,
        expected: KClass<out AvailabilityProviderError>,
    ) = runBlocking {
        val adapter =
            ReserveCaliforniaAvailabilityProvider(
                availabilityClient =
                    object : ReserveCaliforniaAvailabilityClient {
                        override suspend fun fetchGrid(
                            facilityId: Long,
                            startDate: LocalDate,
                            endDate: LocalDate,
                            minDate: LocalDate,
                            maxDate: LocalDate,
                        ): ReserveCaliforniaGridAvailability =
                            throw ReserveCaliforniaException("reservecalifornia HTTP $httpStatus", httpStatus = httpStatus)
                    },
                enabled = true,
            )
        assertThrown(expected) {
            adapter.availability(
                campground = testCampground(bookingProvider = "reservecalifornia", bookingProviderRef = "671:611"),
                campsites = listOf(errorMappingCampsite),
                startDate = start,
                endDate = end,
            )
        }
    }

    private suspend fun assertThrown(
        expected: KClass<out AvailabilityProviderError>,
        block: suspend () -> Unit,
    ) {
        val thrown = assertFailsWith<AvailabilityProviderError> { block() }
        assertEquals(expected, thrown::class, "unexpected classification for: ${thrown.message}")
    }
}

private val errorMappingCampsite =
    campsiteFixture(id = 1, vendor = "aspira", vendorId = "1", bookingProvider = "aspira", bookingProviderRef = null)
