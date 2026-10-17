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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ca.floo.roadtrip.client.recgov.Campsite as RecGovCampsite

/**
 * The fetch-scope / answer-scope seam: a filtered call keeps the whole
 * campground's cells without its answer describing a campsite the caller
 * filtered out.
 */
class CatalogAvailabilityScopeTest {
    private val cabin = campsiteFixture(id = 1, vendor = "recgov", vendorId = "cabin-1")
    private val tent = campsiteFixture(id = 2, vendor = "recgov", vendorId = "tent-1")
    private val window = LocalDate.parse("2026-01-10")

    /** Cabins shut all winter; tents open the day after the window starts. */
    private fun winterFacility(): RecGovAvailabilityClient =
        fakeRecgovClient {
            mapOf(
                "cabin-1" to recgovSite("cabin-1", mapOf("2026-01-10" to "Closed", "2026-01-11" to "Closed")),
                "tent-1" to recgovSite("tent-1", mapOf("2026-01-10" to "Closed", "2026-01-11" to "Available")),
            )
        }

    @Test
    fun `a cabin-scoped call is not told when the tents reopen`() =
        runBlocking {
            val batch =
                RecGovAvailabilityProvider(winterFacility(), enabled = true).catalogAvailability(
                    campground = recgovCampground(),
                    scope = CatalogAvailabilityScope.of(answerFor = listOf(cabin), campgroundCampsites = listOf(cabin, tent)),
                    startDate = window,
                    endDate = window.plusDays(1),
                )

            assertNull(
                batch.seasonBlock,
                "the tents reopening says nothing about the cabins this call answers for",
            )
        }

    @Test
    fun `a tent-scoped call is told when its own kind reopens`() =
        runBlocking {
            val batch =
                RecGovAvailabilityProvider(winterFacility(), enabled = true).catalogAvailability(
                    campground = recgovCampground(),
                    scope = CatalogAvailabilityScope.of(answerFor = listOf(tent), campgroundCampsites = listOf(cabin, tent)),
                    startDate = window,
                    endDate = window.plusDays(1),
                )

            assertEquals("2026-01-11", batch.seasonBlock?.reopensOn)
        }

    @Test
    fun `the reopen date reaches past the window the call asked about`() =
        runBlocking {
            // The vendor hands back whole months, so the fetch already knows a
            // date the one-day window does not cover. Reporting it is the point
            // of a season block.
            val batch =
                RecGovAvailabilityProvider(winterFacility(), enabled = true).catalogAvailability(
                    campground = recgovCampground(),
                    scope = CatalogAvailabilityScope.of(listOf(tent)),
                    startDate = window,
                    endDate = window.plusDays(1),
                )

            assertEquals("2026-01-11", batch.seasonBlock?.reopensOn)
            assertEquals(listOf(window), batch.observations.map { it.date }.distinct())
        }

    @Test
    fun `a filtered call keeps the cells of the campsites it does not answer for`() =
        runBlocking {
            val batch =
                RecGovAvailabilityProvider(winterFacility(), enabled = true).catalogAvailability(
                    campground = recgovCampground(),
                    scope = CatalogAvailabilityScope.of(answerFor = listOf(cabin), campgroundCampsites = listOf(cabin, tent)),
                    startDate = window,
                    endDate = window.plusDays(2),
                )

            // One upstream call covered the facility, so both campsites' cells
            // are in hand and the loader can store them. The response is
            // narrowed above the port, not here.
            assertEquals(setOf(cabin.id, tent.id), batch.observations.mapNotNull { it.campsiteId }.toSet())
            assertEquals(
                AvailabilityStatus.AVAILABLE,
                batch.observations.single { it.campsiteId == tent.id && it.date == window.plusDays(1) }.status,
            )
        }

    @Test
    fun `a campground-wide answer keeps the season block it came back with`() =
        runBlocking {
            // No campsites to tell apart: the adapter's own block is the only
            // one there is, and the reduction must not erase it.
            val batch =
                RecGovAvailabilityProvider(winterFacility(), enabled = true).catalogAvailability(
                    campground = recgovCampground(),
                    scope = CatalogAvailabilityScope.of(emptyList()),
                    startDate = window,
                    endDate = window.plusDays(1),
                )

            assertEquals("2026-01-11", batch.seasonBlock?.reopensOn)
        }

    @Test
    fun `every adapter keeps the cells of the campsites it covers`() =
        runBlocking {
            // One upstream call per campground, so the cells of the campsites a
            // filter dropped cost nothing extra to key — and dropping them is
            // what leaves the next unfiltered check a hole to refetch. Aspira is
            // in here too: its extras sit on a map it is already fetching, which
            // is the only widening its per-map shape makes free.
            for (case in coveredCellCases()) {
                val batch =
                    case.provider.catalogAvailability(
                        campground = case.campground,
                        scope =
                            CatalogAvailabilityScope.of(
                                answerFor = listOf(case.answered),
                                campgroundCampsites = listOf(case.answered, case.alsoCovered),
                            ),
                        startDate = case.startDate,
                        endDate = case.startDate.plusDays(1),
                    )

                assertEquals(
                    setOf(case.answered.id, case.alsoCovered.id),
                    batch.observations.mapNotNull { it.campsiteId }.toSet(),
                    "${case.label} dropped the covered campsite's cells",
                )
            }
        }

    @Test
    fun `aspira's occupancy path does not change when the fetch scope widens`() =
        runBlocking {
            // The occupancy path is chosen when the answered campsites resolve to
            // one resource location. Two campsites on different locations resolve
            // to none — so a wider fetch scope deciding this would silently move
            // a filtered read onto the per-map path and a different classifier.
            var occupancyFetches = 0
            val client =
                fakeAspiraClient(
                    onFetch = { _, mapId, _, _ ->
                        error("the per-map path must not be chosen for mapId=$mapId")
                    },
                    onFetchOccupancy = { _, resourceLocationId, arrival, _ ->
                        occupancyFetches++
                        assertEquals(100, resourceLocationId)
                        assertEquals(LocalDate.parse("2026-06-17"), arrival)
                        AspiraOccupancy(
                            resourceLocationId = resourceLocationId,
                            resourceOccupancy = listOf(AspiraResourceOccupancy(resourceId = 100, availability = 0)),
                        )
                    },
                )
            val adapter =
                AspiraAvailabilityProvider(
                    tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                    availabilityClient = client,
                    enabled = true,
                    occupancyEnabled = true,
                )
            val answered = aspiraCampsite(id = 100, resourceLocationId = 100)
            val other = aspiraCampsite(id = 200, resourceLocationId = 200)

            val batch =
                adapter.catalogAvailability(
                    campground = testCampground(bookingProvider = "aspira", bookingProviderRef = "pc:-2147483630:-2147483388:null"),
                    scope = CatalogAvailabilityScope.of(answerFor = listOf(answered), campgroundCampsites = listOf(answered, other)),
                    startDate = LocalDate.parse("2026-06-17"),
                    endDate = LocalDate.parse("2026-06-18"),
                )

            assertEquals(1, occupancyFetches)
            assertTrue(batch.observations.any { it.campsiteId == answered.id })
        }

    private fun aspiraCampsite(
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

    private fun recgovCampground() = testCampground(bookingProvider = "recgov", bookingProviderRef = "232447")
}

private fun recgovSite(
    id: String,
    availabilities: Map<String, String>,
) = RecGovCampsite(
    id = id,
    site = id,
    loop = "A",
    campsiteType = "STANDARD",
    maxNumPeople = 6,
    equipmentTypes = emptyList(),
    availabilities = availabilities,
)

private fun fakeRecgovClient(month: suspend () -> Map<String, RecGovCampsite>): RecGovAvailabilityClient =
    object : RecGovAvailabilityClient {
        override suspend fun fetchMonth(
            campgroundId: String,
            monthStart: String,
        ): Map<String, RecGovCampsite> = month()
    }

private fun fakeAspiraClient(
    onFetch: suspend (String, Int, LocalDate, LocalDate) -> AspiraAvailability,
    onFetchOccupancy: suspend (String, Int, LocalDate, LocalDate) -> AspiraOccupancy,
): AspiraAvailabilityClient =
    object : AspiraAvailabilityClient {
        override suspend fun fetch(
            host: String,
            mapId: Int,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AspiraAvailability = onFetch(host, mapId, startDate, endDate)

        override suspend fun fetchOccupancy(
            host: String,
            resourceLocationId: Int,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AspiraOccupancy = onFetchOccupancy(host, resourceLocationId, startDate, endDate)
    }

/** One vendor's answer about two campsites, where the call answers for one of them. */
private class CoveredCellCase(
    val label: String,
    val provider: AvailabilityProvider,
    val campground: Campground,
    val answered: Campsite,
    val alsoCovered: Campsite,
    val startDate: LocalDate,
)

private val coveredCellDate: LocalDate = LocalDate.parse("2026-06-01")

private fun coveredCellCases(): List<CoveredCellCase> =
    listOf(
        recgovCoveredCellCase(),
        campflareCoveredCellCase(),
        aspiraCoveredCellCase(),
        reserveAmericaCoveredCellCase(),
        reserveCaliforniaCoveredCellCase(),
    )

private fun recgovCoveredCellCase(): CoveredCellCase {
    val client =
        fakeRecgovClient {
            mapOf(
                "s1" to recgovSite("s1", mapOf(coveredCellDate.toString() to "Available")),
                "s2" to recgovSite("s2", mapOf(coveredCellDate.toString() to "Reserved")),
            )
        }
    return CoveredCellCase(
        label = "recgov",
        provider = RecGovAvailabilityProvider(client, enabled = true),
        campground = testCampground(bookingProvider = "recgov", bookingProviderRef = "232447"),
        answered = campsiteFixture(id = 1, vendor = "recgov", vendorId = "s1"),
        alsoCovered = campsiteFixture(id = 2, vendor = "recgov", vendorId = "s2"),
        startDate = coveredCellDate,
    )
}

private fun campflareCoveredCellCase(): CoveredCellCase {
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
                                    listOf("site-1", "site-2").map { siteId ->
                                        CampflareCampsiteAvailability(
                                            campsiteId = siteId,
                                            availability = mapOf(coveredCellDate to AvailabilityStatus.AVAILABLE),
                                        )
                                    },
                            ),
                    ),
                observedAt = Instant.EPOCH,
            )
        }
    return CoveredCellCase(
        label = "campflare",
        provider = CampflareAvailabilityProvider(client, enabled = true, configured = true),
        campground =
            testCampground(
                bookingProvider = "campflare",
                bookingProviderRef = campgroundId,
                dataProviderRef = DataProviderRef.Campflare(id = campgroundId),
            ),
        answered = campsiteFixture(id = 1, vendor = "campflare", vendorId = "site-1"),
        alsoCovered = campsiteFixture(id = 2, vendor = "campflare", vendorId = "site-2"),
        startDate = coveredCellDate,
    )
}

private fun aspiraCoveredCellCase(): CoveredCellCase {
    val client =
        fakeAspiraClient(
            onFetch = { _, mapId, _, _ ->
                AspiraAvailability(
                    mapId = mapId,
                    parkRollup = emptyList(),
                    byMapLink = emptyMap(),
                    byResource = mapOf("100" to listOf(0), "200" to listOf(1)),
                )
            },
            onFetchOccupancy = { _, _, _, _ -> error("occupancy is off for this case") },
        )
    return CoveredCellCase(
        label = "aspira",
        provider =
            AspiraAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                availabilityClient = client,
                enabled = true,
            ),
        campground = testCampground(bookingProvider = "aspira", bookingProviderRef = "pc:-2147483630:-2147483388:null"),
        answered =
            campsiteFixture(id = 1, vendor = "aspira", vendorId = "pc:100", bookingProvider = "aspira", bookingProviderRef = null),
        alsoCovered =
            campsiteFixture(id = 2, vendor = "aspira", vendorId = "pc:200", bookingProvider = "aspira", bookingProviderRef = null),
        startDate = coveredCellDate,
    )
}

private fun reserveAmericaCoveredCellCase(): CoveredCellCase {
    val client =
        ReserveAmericaAvailabilityClient { _, contractCode, parkId, startDate, endDate ->
            ReserveAmericaAvailability(
                contractCode = contractCode,
                parkId = parkId,
                startDate = startDate,
                endDate = endDate,
                observedAt = Instant.EPOCH,
                statuses =
                    mapOf(
                        "1" to mapOf(coveredCellDate to AvailabilityStatus.AVAILABLE),
                        "2" to mapOf(coveredCellDate to AvailabilityStatus.RESERVED),
                    ),
            )
        }
    return CoveredCellCase(
        label = "reserveamerica",
        provider =
            ReserveAmericaAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA),
                availabilityClient = client,
                enabled = true,
            ),
        campground = testCampground(bookingProvider = "reserveamerica", bookingProviderRef = "NY:489"),
        answered = campsiteFixture(id = 1, vendor = "reserveamerica", vendorId = "1"),
        alsoCovered = campsiteFixture(id = 2, vendor = "reserveamerica", vendorId = "2"),
        startDate = coveredCellDate,
    )
}

private fun reserveCaliforniaCoveredCellCase(): CoveredCellCase {
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
                    statuses =
                        mapOf(
                            "1" to mapOf(coveredCellDate to AvailabilityStatus.AVAILABLE),
                            "2" to mapOf(coveredCellDate to AvailabilityStatus.RESERVED),
                        ),
                )
        }
    return CoveredCellCase(
        label = "reservecalifornia",
        provider = ReserveCaliforniaAvailabilityProvider(availabilityClient = client, enabled = true),
        campground = testCampground(bookingProvider = "reservecalifornia", bookingProviderRef = "690:611"),
        answered = campsiteFixture(id = 1, vendor = "reservecalifornia", vendorId = "1"),
        alsoCovered = campsiteFixture(id = 2, vendor = "reservecalifornia", vendorId = "2"),
        startDate = coveredCellDate,
    )
}
