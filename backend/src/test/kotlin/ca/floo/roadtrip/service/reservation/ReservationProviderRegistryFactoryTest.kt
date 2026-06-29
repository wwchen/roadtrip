package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.recgov.AvailabilityClient
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaTenant
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReservationProviderRegistryFactoryTest {
    @Test
    fun `reserveamerica tenants are built from registry args`() {
        val createdTenants = mutableListOf<ReserveAmericaTenant>()
        val registry =
            ReservationProviderRegistryFactory.build(
                registry =
                    PoiRegistry(
                        dataSources = emptyList(),
                        poiData =
                            listOf(
                                PoiDataEntry(
                                    name = "Test ReserveAmerica",
                                    category = "campground",
                                    etls =
                                        listOf(
                                            EtlEntry(
                                                slug = "test-reserveamerica",
                                                adapter = "ReserveAmericaEtl",
                                                args =
                                                    mapOf(
                                                        "contract" to "ZZ",
                                                        "host" to "example.reserveamerica.test",
                                                        "booking_horizon_days" to "123",
                                                        "provider" to "reserveamerica",
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                recgovClient = stubRecgovClient(),
                aspiraClient = stubAspiraClient(),
                reserveAmericaClientFactory = { tenant ->
                    createdTenants += tenant
                    ReserveAmericaAvailabilityClient { contractCode, parkId, startDate, endDate ->
                        ReserveAmericaAvailability(
                            contractCode = contractCode,
                            parkId = parkId,
                            startDate = startDate,
                            endDate = endDate,
                            observedAt = Instant.EPOCH,
                            statuses = emptyMap(),
                        )
                    }
                },
            )

        val provider = registry.forPoi(row("test-reserveamerica"))

        assertNotNull(provider)
        assertEquals(ReservationProviderId.RESERVEAMERICA, provider.id)
        assertEquals(
            listOf(
                ReserveAmericaTenant(
                    source = "test-reserveamerica",
                    host = "example.reserveamerica.test",
                    contractCode = "ZZ",
                    bookingHorizonDays = 123,
                ),
            ),
            createdTenants,
        )
    }

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")

    private fun stubRecgovClient(): AvailabilityClient =
        object : AvailabilityClient {
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
            ): AspiraAvailability = error("not used")

            override suspend fun fetchOccupancy(
                host: String,
                resourceLocationId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraOccupancy = error("not used")
        }
}
