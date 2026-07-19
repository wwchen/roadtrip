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
import ca.floo.roadtrip.model.availability.campflare.CampflareCampgroundAvailability
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AvailabilityProviderRegistryBuildTest {
    @Test
    fun `reserveamerica provider passes registry tenant args to shared client`() =
        runBlocking {
            var observedCall: ReserveAmericaCall? = null

            val registry =
                AvailabilityProviderRegistry.fromPoiRegistry(
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
                                                    adapter = "ReserveAmericaCampgroundsEtl",
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
                    clients =
                        AvailabilityProviderClients(
                            recgovClient = stubRecgovClient(),
                            aspiraClient = stubAspiraClient(),
                            reserveAmericaClient =
                                ReserveAmericaAvailabilityClient { host, contractCode, parkId, startDate, endDate ->
                                    observedCall = ReserveAmericaCall(host, contractCode, parkId, startDate, endDate)
                                    ReserveAmericaAvailability(
                                        contractCode = contractCode,
                                        parkId = parkId,
                                        startDate = startDate,
                                        endDate = endDate,
                                        observedAt = Instant.EPOCH,
                                        statuses = emptyMap(),
                                    )
                                },
                            reserveCaliforniaClient = stubReserveCaliforniaClient(),
                            campflareClient = stubCampflareClient(),
                        ),
                    isProviderEnabled = allProvidersEnabled,
                )

            val provider = registry.forSource("test-reserveamerica")

            assertNotNull(provider)
            assertEquals(BookingProvider.RESERVEAMERICA, provider.id)
            provider.availability(
                ref = BookingProviderRef.ReserveAmerica(contractCode = "ZZ", parkId = "489"),
                startDate = LocalDate.parse("2026-06-22"),
                endDate = LocalDate.parse("2026-06-24"),
            )

            assertEquals(
                ReserveAmericaCall(
                    host = "example.reserveamerica.test",
                    contractCode = "ZZ",
                    parkId = "489",
                    startDate = LocalDate.parse("2026-06-22"),
                    endDate = LocalDate.parse("2026-06-24"),
                ),
                observedCall,
            )
        }

    @Test
    fun `campflare source and canonical vendor key map to campflare provider`() =
        runBlocking {
            var observedCall: CampflareCall? = null

            val registry =
                AvailabilityProviderRegistry.fromPoiRegistry(
                    registry =
                        PoiRegistry(
                            dataSources = emptyList(),
                            poiData =
                                listOf(
                                    PoiDataEntry(
                                        name = "Campflare Campgrounds",
                                        category = "campground",
                                        etls =
                                            listOf(
                                                EtlEntry(
                                                    slug = "campflare-campgrounds",
                                                    adapter = "CampflareCampgroundsEtl",
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    clients =
                        AvailabilityProviderClients(
                            recgovClient = stubRecgovClient(),
                            aspiraClient = stubAspiraClient(),
                            reserveAmericaClient = stubReserveAmericaClient(),
                            reserveCaliforniaClient = stubReserveCaliforniaClient(),
                            campflareClient =
                                CampflareAvailabilityClient { campgroundIds, startDate, endDate ->
                                    observedCall = CampflareCall(campgroundIds, startDate, endDate)
                                    CampflareAvailability(
                                        campgrounds =
                                            campgroundIds.associateWith {
                                                CampflareCampgroundAvailability(
                                                    campgroundId = it,
                                                    campsiteAvailability = emptyList(),
                                                )
                                            },
                                        observedAt = Instant.EPOCH,
                                    )
                                },
                        ),
                    isProviderEnabled = allProvidersEnabled,
                )

            val provider = registry.forSource("campflare-campgrounds")
            val providerByCanonicalVendor = registry.forSource("campflare")

            assertNotNull(provider)
            assertNotNull(providerByCanonicalVendor)
            assertEquals(BookingProvider.CAMPFLARE, provider.id)
            assertEquals(BookingProvider.CAMPFLARE, providerByCanonicalVendor.id)
            provider.availability(
                ref = BookingProviderRef.Campflare("upper-pines-campground-447"),
                startDate = LocalDate.parse("2026-06-01"),
                endDate = LocalDate.parse("2026-06-07"),
            )
            assertEquals(
                CampflareCall(
                    campgroundIds = listOf("upper-pines-campground-447"),
                    startDate = LocalDate.parse("2026-06-01"),
                    endDate = LocalDate.parse("2026-06-07"),
                ),
                observedCall,
            )
        }

    @Test
    fun `unconfigured campflare provider declines campflare refs so recgov aliases can be fallback`() {
        val disabledProviders = setOf(BookingProvider.CAMPFLARE)
        val registry =
            AvailabilityProviderRegistry.fromPoiRegistry(
                registry =
                    PoiRegistry(
                        dataSources = emptyList(),
                        poiData =
                            listOf(
                                PoiDataEntry(
                                    name = "Campflare Campgrounds",
                                    category = "campground",
                                    etls =
                                        listOf(
                                            EtlEntry(
                                                slug = "campflare-campgrounds",
                                                adapter = "CampflareCampgroundsEtl",
                                            ),
                                        ),
                                ),
                                PoiDataEntry(
                                    name = "Rec.gov Campgrounds",
                                    category = "campground",
                                    etls =
                                        listOf(
                                            EtlEntry(
                                                slug = "recgov-campgrounds",
                                                adapter = "RecGovCampgroundsEtl",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                clients =
                    AvailabilityProviderClients(
                        recgovClient = stubRecgovClient(),
                        aspiraClient = stubAspiraClient(),
                        reserveAmericaClient = stubReserveAmericaClient(),
                        reserveCaliforniaClient = stubReserveCaliforniaClient(),
                        campflareClient = stubCampflareClient(),
                    ),
                isProviderEnabled = { it !in disabledProviders },
            )

        assertNull(registry.forSource("campflare"))
        assertNull(registry.forSource("campflare-campgrounds"))
        assertNull(registry.forBooking(BookingProvider.CAMPFLARE, BookingProviderRef.Campflare("upper-pines-campground-447")))
        assertNull(registry.forBooking(BookingProvider.CAMPFLARE, BookingProviderRef.Campflare("upper-pines-campground-447")))
        assertEquals(
            BookingProvider.RECGOV,
            registry.forBooking(BookingProvider.RECGOV, BookingProviderRef.RecGov(facilityId = "232447"))?.id,
        )
        assertEquals(
            BookingProvider.RECGOV,
            registry.forBooking(BookingProvider.RECGOV, BookingProviderRef.RecGov(facilityId = "232447"))?.id,
        )
    }

    @Test
    fun `availability provider clients close every vendor client through one lifecycle hook`() {
        val closed = mutableListOf<String>()

        val clients =
            AvailabilityProviderClients(
                recgovClient =
                    object : RecGovAvailabilityClient {
                        override suspend fun fetchMonth(
                            campgroundId: String,
                            monthStart: String,
                        ): Map<String, Campsite> = emptyMap()

                        override fun close() {
                            closed += "recgov"
                        }
                    },
                aspiraClient =
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

                        override fun close() {
                            closed += "aspira"
                        }
                    },
                reserveAmericaClient =
                    object : ReserveAmericaAvailabilityClient {
                        override suspend fun fetch(
                            host: String,
                            contractCode: String,
                            parkId: String,
                            startDate: LocalDate,
                            endDate: LocalDate,
                        ): ReserveAmericaAvailability = error("not used")

                        override fun close() {
                            closed += "reserveamerica"
                        }
                    },
                reserveCaliforniaClient =
                    object : ReserveCaliforniaAvailabilityClient {
                        override suspend fun fetchGrid(
                            facilityId: Long,
                            startDate: LocalDate,
                            endDate: LocalDate,
                            minDate: LocalDate,
                            maxDate: LocalDate,
                        ): ReserveCaliforniaGridAvailability = error("not used")

                        override fun close() {
                            closed += "reservecalifornia"
                        }
                    },
                campflareClient =
                    object : CampflareAvailabilityClient {
                        override suspend fun fetchAvailability(
                            campgroundIds: List<String>,
                            startDate: LocalDate,
                            endDate: LocalDate,
                        ): CampflareAvailability = error("not used")

                        override fun close() {
                            closed += "campflare"
                        }
                    },
            )

        clients.close()

        assertEquals(listOf("campflare", "reservecalifornia", "reserveamerica", "aspira", "recgov"), closed)
    }

    private data class ReserveAmericaCall(
        val host: String,
        val contractCode: String,
        val parkId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private data class CampflareCall(
        val campgroundIds: List<String>,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

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
            ): AspiraAvailability = error("not used")

            override suspend fun fetchOccupancy(
                host: String,
                resourceLocationId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraOccupancy = error("not used")
        }

    private fun stubReserveAmericaClient(): ReserveAmericaAvailabilityClient =
        ReserveAmericaAvailabilityClient { _, _, _, _, _ -> error("not used") }

    private fun stubReserveCaliforniaClient(): ReserveCaliforniaAvailabilityClient =
        object : ReserveCaliforniaAvailabilityClient {
            override suspend fun fetchGrid(
                facilityId: Long,
                startDate: LocalDate,
                endDate: LocalDate,
                minDate: LocalDate,
                maxDate: LocalDate,
            ): ReserveCaliforniaGridAvailability = error("not used")
        }

    private fun stubCampflareClient(): CampflareAvailabilityClient = CampflareAvailabilityClient { _, _, _ -> error("not used") }

    private companion object {
        val allProvidersEnabled: (BookingProvider) -> Boolean = { true }
    }
}
