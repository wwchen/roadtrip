package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.client.recgov.Campsite
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvailabilityProviderRegistryTest {
    private val aspiraProvider =
        AspiraAvailabilityProvider(
            tenants = AspiraTenants.all().associateBy { it.vendorCode.removePrefix("aspira_") },
            availabilityClient = stubAspiraClient(),
            enabled = true,
        )

    private val reserveAmericaProvider =
        ReserveAmericaAvailabilityProvider(
            tenants = ReserveAmericaAvailabilityProvider.tenants,
            availabilityClient = stubReserveAmericaClient(),
            enabled = true,
        )

    private val recgovProvider =
        RecGovAvailabilityProvider(
            availabilityClient = stubRecgovClient(),
            enabled = true,
        )

    private val campflareProvider =
        CampflareAvailabilityProvider(
            availabilityClient = stubCampflareClient(),
            enabled = true,
        )

    private val reserveCaliforniaProvider =
        ReserveCaliforniaAvailabilityProvider(
            availabilityClient = stubReserveCaliforniaClient(),
            enabled = true,
        )

    private val providers: List<AvailabilityProvider> =
        listOf(recgovProvider, campflareProvider, reserveCaliforniaProvider, aspiraProvider, reserveAmericaProvider)

    @Test
    fun `aspira routes by tenant code`() {
        val ref = BookingProviderRef.Aspira(tenant = "bc", transactionLocationId = 1, mapId = 2, resourceLocationId = null)
        val matched = providers.firstOrNull { it.supportsRef(ref) }
        assertNotNull(matched)
        assertEquals(BookingProvider.ASPIRA, matched.id)
    }

    @Test
    fun `aspira returns null for unknown tenant`() {
        val ref = BookingProviderRef.Aspira(tenant = "unknown", transactionLocationId = 1, mapId = 2, resourceLocationId = null)
        val matched = providers.firstOrNull { it.supportsRef(ref) }
        assertNull(matched)
    }

    @Test
    fun `reserveamerica routes by contract code`() {
        val ref = BookingProviderRef.ReserveAmerica(contractCode = "ABPP", parkId = "100")
        val matched = providers.firstOrNull { it.supportsRef(ref) }
        assertNotNull(matched)
        assertEquals(BookingProvider.RESERVEAMERICA, matched.id)
    }

    @Test
    fun `reserveamerica returns null for unknown contract`() {
        val ref = BookingProviderRef.ReserveAmerica(contractCode = "UNKNOWN", parkId = "100")
        val matched = providers.firstOrNull { it.supportsRef(ref) }
        assertNull(matched)
    }

    @Test
    fun `recgov routes any recgov ref`() {
        val ref = BookingProviderRef.RecGov(facilityId = "232447")
        val matched = providers.firstOrNull { it.supportsRef(ref) }
        assertNotNull(matched)
        assertEquals(BookingProvider.RECGOV, matched.id)
    }

    @Test
    fun `campflare routes any campflare ref`() {
        val ref = BookingProviderRef.Campflare(campgroundId = "upper-pines")
        val matched = providers.firstOrNull { it.supportsRef(ref) }
        assertNotNull(matched)
        assertEquals(BookingProvider.CAMPFLARE, matched.id)
    }

    @Test
    fun `reservecalifornia routes any rc ref`() {
        val ref = BookingProviderRef.ReserveCalifornia(placeId = 1, facilityIds = listOf(100L))
        val matched = providers.firstOrNull { it.supportsRef(ref) }
        assertNotNull(matched)
        assertEquals(BookingProvider.RESERVECALIFORNIA, matched.id)
    }

    @Test
    fun `disabled provider is invisible to supportsRef`() {
        val disabledCampflare =
            CampflareAvailabilityProvider(
                availabilityClient = stubCampflareClient(),
                enabled = false,
            )
        val list = listOf(disabledCampflare, recgovProvider)
        val ref = BookingProviderRef.Campflare(campgroundId = "test")
        assertNull(list.firstOrNull { it.supportsRef(ref) })
        assertTrue(disabledCampflare.id == BookingProvider.CAMPFLARE)
    }

    private fun stubRecgovClient(): RecGovAvailabilityClient =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, Campsite> = emptyMap()
        }

    private fun stubAspiraClient(): AspiraAvailabilityClient =
        object : AspiraAvailabilityClient {
            override suspend fun fetch(
                host: String,
                mapId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraAvailability = AspiraAvailability(mapId = mapId, parkRollup = emptyList(), byMapLink = emptyMap())

            override suspend fun fetchOccupancy(
                host: String,
                resourceLocationId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraOccupancy = AspiraOccupancy(resourceLocationId = resourceLocationId)
        }

    private fun stubReserveAmericaClient(): ReserveAmericaAvailabilityClient =
        ReserveAmericaAvailabilityClient { _, contractCode, parkId, startDate, endDate ->
            ReserveAmericaAvailability(
                contractCode = contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
                observedAt = Instant.EPOCH,
                statuses = emptyMap(),
            )
        }

    private fun stubReserveCaliforniaClient(): ReserveCaliforniaAvailabilityClient =
        object : ReserveCaliforniaAvailabilityClient {
            override suspend fun fetchGrid(
                facilityId: Long,
                startDate: LocalDate,
                endDate: LocalDate,
                minDate: LocalDate,
                maxDate: LocalDate,
            ): ReserveCaliforniaGridAvailability =
                ReserveCaliforniaGridAvailability(facilityId = facilityId, observedAt = Instant.EPOCH, statuses = emptyMap())
        }

    private fun stubCampflareClient(): CampflareAvailabilityClient =
        CampflareAvailabilityClient { _, _, _ ->
            CampflareAvailability(campgrounds = emptyMap(), observedAt = Instant.EPOCH)
        }
}
