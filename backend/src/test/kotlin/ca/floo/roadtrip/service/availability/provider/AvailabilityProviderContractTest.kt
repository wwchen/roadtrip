package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.recgov.Campsite
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.model.domain.ProviderRef
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AvailabilityProviderContractTest {
    @Test
    fun `all reservation vendor adapters implement availability provider contract`() {
        val providers: List<AvailabilityProvider> =
            listOf(
                RecGovAvailabilityProvider(client = stubRecgovClient(), enabled = true),
                AspiraAvailabilityProvider(
                    tenant =
                        AspiraTenant(
                            host = "reservation.pc.gc.ca",
                            vendorCode = "aspira_pc",
                            bookingHorizonDays = 365,
                        ),
                    client = stubAspiraClient(),
                    enabled = true,
                ),
                ReserveAmericaAvailabilityProvider(
                    tenant =
                        ReserveAmericaTenant(
                            source = "new-york-state-parks",
                            host = "newyorkstateparks.reserveamerica.com",
                            contractCode = "NY",
                            bookingHorizonDays = 270,
                        ),
                    client = stubReserveAmericaClient(),
                    enabled = true,
                ),
                ReserveCaliforniaAvailabilityProvider(client = stubReserveCaliforniaClient(), enabled = true),
            )

        assertEquals(4, providers.size)
    }

    @Test
    fun `availability provider accepts direct arguments instead of request wrappers`() =
        runBlocking {
            val provider: AvailabilityProvider = RecGovAvailabilityProvider(client = stubRecgovClient(), enabled = true)
            val startDate = LocalDate.parse("2026-07-01")
            val endDate = LocalDate.parse("2026-07-02")

            val availability =
                provider.availability(
                    ref = ProviderRef.RecGov("232447"),
                    startDate = startDate,
                    endDate = endDate,
                )
            val catalog =
                provider.catalogAvailability(
                    ref = ProviderRef.RecGov("232447"),
                    campsites = emptyList(),
                    startDate = startDate,
                    endDate = endDate,
                )

            assertEquals("recgov", availability.provider)
            assertEquals("recgov", catalog.provider)
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
            ): AspiraAvailability =
                AspiraAvailability(
                    mapId = mapId,
                    parkRollup = emptyList(),
                    byMapLink = emptyMap(),
                )

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
                ReserveCaliforniaGridAvailability(
                    facilityId = facilityId,
                    observedAt = Instant.EPOCH,
                    statuses = emptyMap(),
                )
        }
}
