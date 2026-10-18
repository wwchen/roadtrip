package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.aspira.AspiraResourceOccupancy
import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.client.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampgroundAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampsiteAvailability
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ca.floo.roadtrip.client.recgov.Campsite as RecGovCampsite

class AvailabilityProviderContractTest {
    @Test
    fun `all reservation vendor adapters implement availability provider contract`() {
        val providers: List<AvailabilityProvider> =
            listOf(
                RecGovAvailabilityProvider(availabilityClient = stubRecgovClient(), enabled = true),
                AspiraAvailabilityProvider(
                    tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                    availabilityClient = stubAspiraClient(),
                    enabled = true,
                ),
                ReserveAmericaAvailabilityProvider(
                    tenants = shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA),
                    availabilityClient = stubReserveAmericaClient(),
                    enabled = true,
                ),
                ReserveCaliforniaAvailabilityProvider(availabilityClient = stubReserveCaliforniaClient(), enabled = true),
            )

        assertEquals(4, providers.size)
    }

    @Test
    fun `availability provider takes a campground, its campsites and a window, not a request wrapper`() =
        runBlocking {
            val provider: AvailabilityProvider = RecGovAvailabilityProvider(availabilityClient = stubRecgovClient(), enabled = true)
            val startDate = LocalDate.parse("2026-07-01")
            val endDate = LocalDate.parse("2026-07-02")

            val batch =
                provider.availability(
                    campground = testCampground(bookingProvider = "recgov", bookingProviderRef = "232447"),
                    campsites = emptyList(),
                    startDate = startDate,
                    endDate = endDate,
                )

            assertEquals("recgov", batch.provider)
        }

    @Test
    fun `every adapter answers for every campsite it is given, and keys every observation`() =
        runBlocking {
            // The port's contract, the same for all five: base-unit facts over the
            // campsites of the campground. An adapter that answered for fewer
            // would leave the rest of the campground with no stored cell, and the
            // next read would call the vendor again; one that returned a row it
            // could not key would return something nothing above can store.
            for (case in portContractCases()) {
                val batch =
                    case.provider.availability(
                        campground = case.campground,
                        campsites = case.campsites,
                        startDate = portContractDate,
                        endDate = portContractDate.plusDays(1),
                    )

                assertEquals(
                    case.campsites.mapTo(mutableSetOf()) { it.id },
                    batch.observations.mapNotNullTo(mutableSetOf()) { it.campsiteId },
                    "${case.label} did not answer for every campsite it was given",
                )
                assertTrue(
                    batch.observations.none { it.campsiteId == null },
                    "${case.label} returned an observation that names no campsite",
                )
            }
        }

    @Test
    fun `aspira's occupancy path follows the campground's ref, not the campsites it is handed`() =
        runBlocking {
            // The occupancy path is a capability of the campground — its ref names
            // a resource location or it does not. It used to be chosen when the
            // campsites the caller happened to pass all sat under one location, so
            // the same campground took different paths, with different
            // classifiers, depending on who asked.
            var occupancyFetches = 0
            var mapFetches = 0
            val client =
                occupancyAwareAspiraClient(
                    onFetch = { mapFetches++ },
                    onFetchOccupancy = { occupancyFetches++ },
                )
            val adapter =
                AspiraAvailabilityProvider(
                    tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                    availabilityClient = client,
                    enabled = true,
                    occupancyEnabled = true,
                )
            val onOneLocation = listOf(aspiraContractCampsite(1, ASPIRA_REF_RESOURCE_LOCATION_ID))
            val onTwoLocations = onOneLocation + aspiraContractCampsite(2, 200)

            // A ref with no resource location: the per-map path, whatever the list.
            for (campsites in listOf(onOneLocation, onTwoLocations)) {
                adapter.availability(
                    campground = testCampground(bookingProvider = "aspira", bookingProviderRef = "pc:-2147483630:-2147483388:null"),
                    campsites = campsites,
                    startDate = portContractDate,
                    endDate = portContractDate.plusDays(1),
                )
            }
            assertEquals(0, occupancyFetches, "a campground whose ref names no resource location has no occupancy path")

            // A ref that names one: the occupancy path, whatever the list.
            for (campsites in listOf(onOneLocation, onTwoLocations)) {
                adapter.availability(
                    campground =
                        testCampground(
                            bookingProvider = "aspira",
                            bookingProviderRef = "pc:-2147483630:-2147483388:$ASPIRA_REF_RESOURCE_LOCATION_ID",
                        ),
                    campsites = campsites,
                    startDate = portContractDate,
                    endDate = portContractDate.plusDays(1),
                )
            }
            assertEquals(2, occupancyFetches, "the campground's own resource location decides this, not the caller's list")
            assertTrue(mapFetches > 0, "the per-map path was still exercised above")
        }

    @Test
    fun `aspira's occupancy answer is UNKNOWN for a resource it did not mention`() =
        runBlocking {
            // The occupancy response enumerates the resources under the location
            // it was asked about, so a target it does not mention was asked about
            // and not mentioned. The campsites carry no location of their own to
            // narrow the targets by: `AspiraCatalogCampsite.resourceLocationId`
            // holds the resource id, in a different namespace from the location
            // id on a campground's ref.
            val adapter =
                AspiraAvailabilityProvider(
                    tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                    availabilityClient = occupancyAwareAspiraClient(byResource = mapOf("100" to listOf(0))),
                    enabled = true,
                    occupancyEnabled = true,
                )
            val mentioned = aspiraContractCampsite(1, 100)
            val unmentioned = aspiraContractCampsite(2, 200)

            val batch =
                adapter.availability(
                    campground =
                        testCampground(
                            bookingProvider = "aspira",
                            bookingProviderRef = "pc:-2147483630:-2147483388:$ASPIRA_REF_RESOURCE_LOCATION_ID",
                        ),
                    campsites = listOf(mentioned, unmentioned),
                    startDate = portContractDate,
                    endDate = portContractDate.plusDays(1),
                )

            val byCampsite = batch.observations.associateBy { it.campsiteId }
            assertEquals(setOf(mentioned.id, unmentioned.id), byCampsite.keys.filterNotNullTo(mutableSetOf()))
            assertEquals(AvailabilityStatus.AVAILABLE, byCampsite[mentioned.id]?.status)
            assertEquals(AvailabilityStatus.UNKNOWN, byCampsite[unmentioned.id]?.status)
        }

    private fun stubRecgovClient(): RecGovAvailabilityClient =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, RecGovCampsite> = emptyMap()
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

private val portContractDate: LocalDate = LocalDate.parse("2026-06-01")

/** The resource location the test campground's own ref names. */
private const val ASPIRA_REF_RESOURCE_LOCATION_ID = 100

/** One vendor's adapter, a campground it claims, and the campsites of it. */
private class PortContractCase(
    val label: String,
    val provider: AvailabilityProvider,
    val campground: Campground,
    val campsites: List<Campsite>,
)

private fun portContractCases(): List<PortContractCase> =
    listOf(
        recgovPortCase(),
        campflarePortCase(),
        aspiraPortCase(),
        reserveAmericaPortCase(),
        reserveCaliforniaPortCase(),
    )

private fun recgovPortCase(): PortContractCase {
    // Only one of the two campsites is in the payload: the other is a catalog row
    // the facility no longer lists, and "asked and not mentioned" is UNKNOWN —
    // still a fact about a base unit.
    val client =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, RecGovCampsite> =
                mapOf(
                    "s1" to
                        RecGovCampsite(
                            id = "s1",
                            site = "s1",
                            loop = "A",
                            campsiteType = "STANDARD",
                            maxNumPeople = 6,
                            equipmentTypes = emptyList(),
                            availabilities = mapOf(portContractDate.toString() to "Available"),
                        ),
                )
        }
    return PortContractCase(
        label = "recgov",
        provider = RecGovAvailabilityProvider(client, enabled = true),
        campground = testCampground(bookingProvider = "recgov", bookingProviderRef = "232447"),
        campsites =
            listOf(
                campsiteFixture(id = 1, vendor = "recgov", vendorId = "s1"),
                campsiteFixture(id = 2, vendor = "recgov", vendorId = "s2"),
            ),
    )
}

private fun campflarePortCase(): PortContractCase {
    val campgroundId = "upper-pines-campground-447"
    val client =
        CampflareAvailabilityClient { _, _, _ ->
            CampflareAvailability(
                campgrounds =
                    mapOf(
                        campgroundId to
                            CampflareCampgroundAvailability(
                                campgroundId = campgroundId,
                                campsiteAvailability =
                                    listOf(
                                        CampflareCampsiteAvailability(
                                            campsiteId = "site-1",
                                            availability = mapOf(portContractDate to AvailabilityStatus.AVAILABLE),
                                        ),
                                    ),
                            ),
                    ),
                observedAt = Instant.EPOCH,
            )
        }
    return PortContractCase(
        label = "campflare",
        provider = CampflareAvailabilityProvider(client, enabled = true, configured = true),
        campground =
            testCampground(
                bookingProvider = "campflare",
                bookingProviderRef = campgroundId,
                dataProviderRef = DataProviderRef.Campflare(id = campgroundId),
            ),
        campsites =
            listOf(
                campsiteFixture(id = 1, vendor = "campflare", vendorId = "site-1"),
                campsiteFixture(id = 2, vendor = "campflare", vendorId = "site-2"),
            ),
    )
}

private fun aspiraPortCase(): PortContractCase =
    PortContractCase(
        label = "aspira",
        provider =
            AspiraAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                availabilityClient =
                    occupancyAwareAspiraClient(
                        byResource = mapOf("100" to listOf(0)),
                    ),
                enabled = true,
            ),
        campground = testCampground(bookingProvider = "aspira", bookingProviderRef = "pc:-2147483630:-2147483388:null"),
        campsites = listOf(aspiraContractCampsite(1, 100), aspiraContractCampsite(2, 200)),
    )

private fun reserveAmericaPortCase(): PortContractCase {
    val client =
        ReserveAmericaAvailabilityClient { _, contractCode, parkId, startDate, endDate ->
            ReserveAmericaAvailability(
                contractCode = contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
                observedAt = Instant.EPOCH,
                statuses = mapOf("1" to mapOf(portContractDate to AvailabilityStatus.AVAILABLE)),
            )
        }
    return PortContractCase(
        label = "reserveamerica",
        provider =
            ReserveAmericaAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA),
                availabilityClient = client,
                enabled = true,
            ),
        campground = testCampground(bookingProvider = "reserveamerica", bookingProviderRef = "NY:489"),
        campsites =
            listOf(
                campsiteFixture(id = 1, vendor = "reserveamerica", vendorId = "1"),
                campsiteFixture(id = 2, vendor = "reserveamerica", vendorId = "2"),
            ),
    )
}

private fun reserveCaliforniaPortCase(): PortContractCase {
    val client =
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
                    statuses = mapOf("1" to mapOf(portContractDate to AvailabilityStatus.AVAILABLE)),
                )
        }
    return PortContractCase(
        label = "reservecalifornia",
        provider = ReserveCaliforniaAvailabilityProvider(availabilityClient = client, enabled = true),
        campground = testCampground(bookingProvider = "reservecalifornia", bookingProviderRef = "690:611"),
        campsites =
            listOf(
                campsiteFixture(id = 1, vendor = "reservecalifornia", vendorId = "1"),
                campsiteFixture(id = 2, vendor = "reservecalifornia", vendorId = "2"),
            ),
    )
}

private fun aspiraContractCampsite(
    id: Long,
    resourceLocationId: Int,
): Campsite =
    campsiteFixture(
        id = id,
        vendor = "aspira",
        vendorId = "pc:$resourceLocationId",
        bookingProvider = "aspira",
        bookingProviderRef = null,
    )

private fun occupancyAwareAspiraClient(
    byResource: Map<String, List<Int>> = mapOf("100" to listOf(0), "200" to listOf(0)),
    onFetch: () -> Unit = {},
    onFetchOccupancy: () -> Unit = {},
): AspiraAvailabilityClient =
    object : AspiraAvailabilityClient {
        override suspend fun fetch(
            host: String,
            mapId: Int,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AspiraAvailability {
            onFetch()
            return AspiraAvailability(
                mapId = mapId,
                parkRollup = emptyList(),
                byMapLink = emptyMap(),
                byResource = byResource,
            )
        }

        override suspend fun fetchOccupancy(
            host: String,
            resourceLocationId: Int,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AspiraOccupancy {
            onFetchOccupancy()
            return AspiraOccupancy(
                resourceLocationId = resourceLocationId,
                resourceOccupancy =
                    byResource.keys.map { AspiraResourceOccupancy(resourceId = it.toLong(), availability = 0) },
            )
        }
    }
