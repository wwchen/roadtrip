package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.campgroundWideBatch
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testCampsiteCatalogService
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_POI_ID = 1L
private const val WINDOW_START_OFFSET_DAYS = 1L
private const val WINDOW_LENGTH_DAYS = 7L
private val fixedNow: Instant = Instant.parse("2026-07-30T12:00:00Z")
private val freshnessTtl: Duration = Duration.ofMinutes(30)

/**
 * The payoff for the fetch-scope / answer-scope split: a `site_type` check and
 * the unfiltered check that follows it cost one upstream call between them,
 * because the first one's batch covered the campground and the store kept it.
 */
class FilteredThenUnfilteredCheckTest : SharedDbTest() {
    @Test
    fun `a filtered check then an unfiltered one over the same window is one provider call`() {
        val fixture = checkFixture()

        val filtered =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.TENT),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }
        val unfiltered =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = emptyList(),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }

        assertEquals(1, fixture.provider.catalogCalls.size, "the unfiltered check called the vendor again")
        // The tent check answered for the tents alone and asked to keep the rest.
        val scope =
            fixture.provider.catalogCalls
                .single()
                .scope
        assertEquals(2, scope.answerFor.size)
        assertEquals(2, scope.alsoCover.size)

        assertEquals(2, filtered.campsites.size)
        assertFalse(filtered.batch!!.cacheBlock.hit, "the first check had nothing stored to serve")
        assertEquals(4, unfiltered.campsites.size)
        assertTrue(unfiltered.batch!!.cacheBlock.hit, "the whole campground's cells were already stored")
        assertEquals(
            setOf(AvailabilityStatus.AVAILABLE),
            unfiltered.batch!!
                .observations
                .map { it.status }
                .toSet(),
        )
        assertEquals(
            fixture.allCampsiteIds,
            unfiltered.batch!!
                .observations
                .mapNotNull { it.campsiteId }
                .toSet(),
        )
    }

    @Test
    fun `the filtered check's response is only about the campsites it answered for`() {
        val fixture = checkFixture()

        val filtered =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.TENT),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }

        // The batch covered four campsites; the answer is about two.
        assertEquals(
            filtered.campsites.map { it.id }.toSet(),
            filtered.batch!!
                .observations
                .mapNotNull { it.campsiteId }
                .toSet(),
        )
    }

    private fun checkFixture(): CheckFixture {
        ctx.cleanCanonicalCatalogFixtures()
        val catalog =
            ctx.seedCatalogPoi(
                sourceId = "one-call-poi",
                name = "One Call CG",
                lon = -119.56,
                lat = 37.74,
                providerRefJson = """{"recgov_id": "232447"}""",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        val kinds = listOf(CampsiteKind.TENT, CampsiteKind.TENT, CampsiteKind.CABIN, CampsiteKind.CABIN)
        val campsiteIds =
            kinds
                .mapIndexed { index, kind ->
                    ctx.seedCampsite(campgroundId = catalog.catalogId, vendorId = "one-call-$index", kind = kind.wire)
                }.toSet()

        val provider =
            FakeAvailabilityProvider(
                BookingProvider.RECGOV,
                onCatalogAvailability = { call ->
                    campgroundWideBatch(call, BookingProvider.RECGOV, observedAt = fixedNow)
                },
            )
        val providers: List<AvailabilityProvider> = listOf(provider)
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

        val campsitesRepo = CampsiteRepo(ctx)
        val campgroundRepo = CampgroundRepo(ctx)
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = campgroundRepo,
                availabilityProviders = providers,
                dateResolver = dateResolver,
                pollerRepo = AvailabilityPollerRepo(ctx),
            )

        val controller =
            CampsiteAvailabilityController(
                campgroundRepo = campgroundRepo,
                campsitesRepo = campsitesRepo,
                catalogService = testCampsiteCatalogService(ctx, campsitesRepo, targets),
                availabilityService =
                    CampsiteAvailabilityService(
                        availabilityProviders = providers,
                        dateResolver = dateResolver,
                        // The real fetcher, so the call that is being counted is
                        // the one the port actually makes.
                        failoverFetcher = FailoverAvailabilityFetcher(ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))),
                        bookingHorizons = BookingHorizonResolver(providers, dateResolver),
                        availabilityRepo = AvailabilityRepo(ctx),
                        clock = clock,
                        snapshotFreshnessTtl = { freshnessTtl },
                    ),
                dateResolver = dateResolver,
                watchCapabilityService =
                    WatchCapabilityService(
                        availabilityTargets = targets,
                        bookingTargets = AvailabilityBookingTargetResolver(BookingAdapterRegistry(emptyList())),
                        tenants = shippedTenantRegistry(),
                    ),
            )
        val start = dateResolver.contextForPoi(TEST_POI_ID).earliestDate.plusDays(WINDOW_START_OFFSET_DAYS)
        return CheckFixture(
            controller = controller,
            provider = provider,
            allCampsiteIds = campsiteIds,
            startDate = start,
            endDate = start.plusDays(WINDOW_LENGTH_DAYS),
        )
    }
}

private class CheckFixture(
    val controller: CampsiteAvailabilityController,
    val provider: FakeAvailabilityProvider,
    val allCampsiteIds: Set<Long>,
    val startDate: LocalDate,
    val endDate: LocalDate,
)
