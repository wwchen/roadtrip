package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.cache.CachedAspiraAvailability
import ca.floo.roadtrip.clients.cache.CachedRecGovAvailability
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.reserveamerica.CachedReserveAmericaAvailability
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
                recgovCache = CachedRecGovAvailability(fetchMonth = { _: String, _: String -> emptyMap<String, Campsite>() }),
                aspiraCache =
                    CachedAspiraAvailability(
                        fetcher = { _: String, _: Int, _: LocalDate, _: LocalDate -> error("not used") },
                    ),
                reserveAmericaCacheFactory = { tenant ->
                    createdTenants += tenant
                    CachedReserveAmericaAvailability(
                        client =
                            ReserveAmericaAvailabilityClient { contractCode, parkId, startDate, endDate ->
                                ReserveAmericaAvailability(
                                    contractCode = contractCode,
                                    parkId = parkId,
                                    startDate = startDate,
                                    endDate = endDate,
                                    observedAt = Instant.EPOCH,
                                    statuses = emptyMap(),
                                )
                            },
                    )
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
}
