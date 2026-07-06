package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReservationProviderRegistryFactoryTest {
    @Test
    fun `reserveamerica provider passes registry tenant args to shared client`() =
        runBlocking {
            var observedCall: ReserveAmericaCall? = null

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
                    clients =
                        ReservationProviderClients(
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
                        ),
                )

            val provider = registry.forPoi(row("test-reserveamerica"))

            assertNotNull(provider)
            assertEquals(ReservationProviderId.RESERVEAMERICA, provider.id)
            provider.availability(
                ref = ProviderRef.ReserveAmerica(contractCode = "ZZ", parkId = "489"),
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
    fun `reservation provider clients close every vendor client through one lifecycle hook`() {
        val closed = mutableListOf<String>()

        val clients =
            ReservationProviderClients(
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
            )

        clients.close()

        assertEquals(listOf("reservecalifornia", "reserveamerica", "aspira", "recgov"), closed)
    }

    private data class ReserveAmericaCall(
        val host: String,
        val contractCode: String,
        val parkId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")

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
}
