package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.models.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.models.availability.campflare.CampflareCampgroundAvailability
import ca.floo.roadtrip.models.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.models.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.Fetcher
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.availability.provider.adapters.campflare.CampflareAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaTenant
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaAvailabilityProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TEST_DATA_SOURCE = "test-source"

class AvailabilityProviderSourceResolutionTest {
    @Test
    fun `reserveamerica provider passes tenant args to shared client`() =
        runBlocking {
            var observedCall: ReserveAmericaCall? = null
            val provider =
                ReserveAmericaAvailabilityProvider(
                    tenant =
                        ReserveAmericaTenant(
                            source = "test-reserveamerica",
                            host = "example.reserveamerica.test",
                            contractCode = "ZZ",
                            bookingHorizonDays = 123,
                        ),
                    client =
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
                    enabled = true,
                )
            val providersBySource =
                availabilityProvidersBySource(
                    poiRegistry =
                        registryWithPoiData(
                            source = "test-reserveamerica",
                            adapter = "ReserveAmericaEtl",
                            args =
                                mapOf(
                                    "host" to "example.reserveamerica.test",
                                    "contract" to "ZZ",
                                    "booking_horizon_days" to "123",
                                ),
                        ),
                    providers = providerSet(provider),
                )

            val resolved = providersBySource.availabilityProviderFor(row("test-reserveamerica"))

            assertNotNull(resolved)
            assertEquals(AvailabilityProviderId.RESERVEAMERICA, resolved.id)
            resolved.availability(
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
    fun `campflare source and canonical vendor key map to campflare provider`() =
        runBlocking {
            var observedCall: CampflareCall? = null
            val provider =
                CampflareAvailabilityProvider(
                    client =
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
                    enabled = true,
                )
            val providersBySource =
                availabilityProvidersBySource(
                    poiRegistry = registryWithPoiData(source = "campflare-campgrounds", adapter = "CampflareCampgroundsEtl"),
                    providers = providerSet(campflare = provider),
                )

            val resolved = providersBySource.availabilityProviderFor(row("campflare-campgrounds"))
            val resolvedByCanonicalVendor = providersBySource.availabilityProviderFor(row("campflare"))

            assertNotNull(resolved)
            assertNotNull(resolvedByCanonicalVendor)
            assertEquals(AvailabilityProviderId.CAMPFLARE, resolved.id)
            assertEquals(AvailabilityProviderId.CAMPFLARE, resolvedByCanonicalVendor.id)
            resolved.availability(
                ref = ProviderRef.Campflare("upper-pines-campground-447"),
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
        val campflare = CampflareAvailabilityProvider(client = stubCampflareClient(), enabled = false)
        val recgov = RecGovAvailabilityProvider(client = stubRecgovClient(), enabled = true)
        val providersBySource =
            availabilityProvidersBySource(
                poiRegistry =
                    registryWithPoiData(
                        "campflare-campgrounds" to "CampflareCampgroundsEtl",
                        "federal-campgrounds" to "RecGovCampgroundsEtl",
                    ),
                providers = providerSet(recgov = recgov, campflare = campflare),
            )

        assertNull(providersBySource.availabilityProviderFor(row("campflare")))
        assertNull(providersBySource.availabilityProviderFor(row("campflare-campgrounds")))
        assertNull(providersBySource.availabilityProviderFor(row("campflare"), ProviderRef.Campflare("upper-pines-campground-447")))
        assertNull(
            providersBySource.availabilityProviderFor(
                row("campflare-campgrounds"),
                ProviderRef.Campflare("upper-pines-campground-447"),
            ),
        )
        assertEquals(
            AvailabilityProviderId.RECGOV,
            providersBySource.availabilityProviderFor(row("recgov"), ProviderRef.RecGov("232447"))?.id,
        )
        assertEquals(
            AvailabilityProviderId.RECGOV,
            providersBySource.availabilityProviderFor(row("federal-campgrounds"), ProviderRef.RecGov("232447"))?.id,
        )
    }

    @Test
    fun `duplicate source bindings fail fast`() {
        val recgov = RecGovAvailabilityProvider(client = stubRecgovClient(), enabled = true)

        assertFailsWith<IllegalArgumentException> {
            availabilityProvidersBySource(
                poiRegistry =
                    registryWithPoiData(
                        "recgov-campgrounds" to "RecGovCampgroundsEtl",
                        "recgov" to "CampflareCampgroundsEtl",
                    ),
                providers = providerSet(recgov = recgov),
            )
        }
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

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")

    private fun providerSet(
        vararg extra: AvailabilityProvider,
        recgov: RecGovAvailabilityProvider = RecGovAvailabilityProvider(client = stubRecgovClient(), enabled = true),
        campflare: CampflareAvailabilityProvider = CampflareAvailabilityProvider(client = stubCampflareClient(), enabled = true),
        reserveCalifornia: ReserveCaliforniaAvailabilityProvider =
            ReserveCaliforniaAvailabilityProvider(client = stubReserveCaliforniaClient(), enabled = true),
    ): List<AvailabilityProvider> = listOf(recgov, campflare, reserveCalifornia) + extra

    private fun registryWithPoiData(vararg rows: Pair<String, String>): PoiRegistry =
        PoiRegistry(
            dataSources = listOf(dataSource()),
            poiData = rows.map { (source, adapter) -> poiData(source = source, adapter = adapter) },
        )

    private fun registryWithPoiData(
        source: String,
        adapter: String,
        args: Map<String, String> = emptyMap(),
    ): PoiRegistry =
        PoiRegistry(
            dataSources = listOf(dataSource()),
            poiData = listOf(poiData(source = source, adapter = adapter, args = args)),
        )

    private fun dataSource(): DataSourceEntry =
        DataSourceEntry(
            slug = TEST_DATA_SOURCE,
            name = "Test Source",
            fetcher =
                Fetcher(
                    executor = "test",
                    filename = "test.json",
                    outputDirPrefix = "test",
                ),
        )

    private fun poiData(
        source: String,
        adapter: String,
        args: Map<String, String> = emptyMap(),
    ): PoiDataEntry =
        PoiDataEntry(
            name = source,
            category = "campground",
            etls =
                listOf(
                    EtlEntry(
                        slug = source,
                        adapter = adapter,
                        inputs = listOf(TEST_DATA_SOURCE),
                        args = args,
                    ),
                ),
        )

    private fun stubRecgovClient(): RecGovAvailabilityClient =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, Campsite> = emptyMap()
        }

    private fun stubCampflareClient(): CampflareAvailabilityClient = CampflareAvailabilityClient { _, _, _ -> error("not used") }

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
