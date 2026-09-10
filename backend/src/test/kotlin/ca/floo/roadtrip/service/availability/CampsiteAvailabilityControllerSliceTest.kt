package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FAKE_PROVIDER_HORIZON_DAYS
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.RefLinkRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.ref.DbRefResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_POI_ID = 1L

/** Offsets from the resolver's own earliest bookable date, so the window never goes stale. */
private const val WINDOW_START_OFFSET_DAYS = 1L
private const val WINDOW_LENGTH_DAYS = 7L

/** Enough sites that a per-campsite lookup would be an obvious cost. */
private const val BUSY_CAMPGROUND_SITES = 50

class CampsiteAvailabilityControllerSliceTest : SharedDbTest() {
    @Test
    fun `slice carries the resolved window and the filtered campsites`() {
        val fixture = sliceTestController(siteTypes = listOf(CampsiteKind.TENT, CampsiteKind.RV))

        val slice =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.TENT),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }

        assertEquals(fixture.startDate, slice.startDate)
        assertEquals(fixture.endDate, slice.endDate)
        assertEquals(fixture.earliestDate, slice.earliestDate)
        // The horizon comes from the serving provider's capabilities.
        assertEquals(fixture.earliestDate.plusDays(FAKE_PROVIDER_HORIZON_DAYS.toLong()), slice.latestDate)
        assertEquals(2, slice.allCampsites.size)
        assertEquals(1, slice.campsites.size)
        assertNotNull(slice.batch)
    }

    @Test
    fun `slice has a null batch when no campsite matches the site type filter`() {
        val fixture = sliceTestController(siteTypes = listOf(CampsiteKind.TENT))

        val slice =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.CABIN),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }

        assertEquals(0, slice.campsites.size)
        assertNull(slice.batch)
        // The filter matched nothing, but the campground still has a serving
        // provider, so the horizon comes from its real booking horizon.
        assertEquals(fixture.earliestDate.plusDays(FAKE_PROVIDER_HORIZON_DAYS.toLong()), slice.latestDate)
    }

    @Test
    fun `shaping a many-campsite slice resolves no per-campsite target`() {
        val fixture = sliceTestController(siteTypes = List(BUSY_CAMPGROUND_SITES) { CampsiteKind.TENT })

        val slice =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.TENT),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }
        val fused = fusePoiWindow(slice, slice.earliestDate)

        assertEquals(BUSY_CAMPGROUND_SITES, slice.campsites.size)
        assertEquals(
            BUSY_CAMPGROUND_SITES,
            fused.days
                .first()
                .cells.size,
        )
        assertEquals(BUSY_CAMPGROUND_SITES, slice.perCampsiteEnvelopes().size)
        // The serving provider was picked once for the slice; nothing asked the
        // target resolver about a campsite, at any campground size.
        assertEquals(0, fixture.targets.campsiteResolves)
        assertTrue(slice.pollingSupported)
    }

    /**
     * The detail endpoint's real per-campsite cost, measured end to end.
     *
     * `availabilityForPoi` is the whole path — slice, watch capabilities,
     * fusion — and watch capabilities are the only part that needs a
     * per-campsite target at all. One resolution per campsite is the floor;
     * this pins it there, because the three capability questions each used to
     * walk the scope for themselves and charge 3N.
     */
    @Test
    fun `a detail request resolves each campsite exactly once`() {
        val fixture = sliceTestController(siteTypes = List(BUSY_CAMPGROUND_SITES) { CampsiteKind.TENT })

        val response =
            runBlocking {
                fixture.controller.availabilityForPoi(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.TENT),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }

        assertEquals(
            BUSY_CAMPGROUND_SITES,
            response.days
                .first()
                .cells.size,
        )
        assertEquals(BUSY_CAMPGROUND_SITES, fixture.targets.campsiteResolves)
    }

    @Test
    fun `slice reports no polling support when the serving provider cannot poll`() {
        val fixture =
            sliceTestController(
                siteTypes = listOf(CampsiteKind.TENT),
                providers = listOf(FakeAvailabilityProvider(BookingProvider.RECGOV, supportsInternalPolling = false)),
            )

        val slice =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.TENT),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }

        assertFalse(slice.pollingSupported)
    }

    @Test
    fun `slice has no latest date when no provider claims the campground`() {
        val fixture = sliceTestController(siteTypes = listOf(CampsiteKind.TENT), providers = emptyList())

        val slice =
            runBlocking {
                fixture.controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf(CampsiteKind.CABIN),
                    startDate = fixture.startDate,
                    endDate = fixture.endDate,
                )
            }

        assertNull(slice.latestDate)
    }

    /**
     * Builds a controller over a fresh POI/campground with one seeded campsite
     * per entry in [siteTypes]. Mirrors the fake repos/services
     * `CampsiteAvailabilityServiceTest` builds: real repos over the shared test
     * DB, a [FakeAvailabilityProvider] standing in for rec.gov, and a
     * failover fetcher stubbed to answer with a canned batch instead of
     * calling out. `catalogService` and `watchCapabilityService` are wired
     * with real implementations because the constructor requires them, and
     * `watchCapabilityService` now shares `targets`, so it is load-bearing
     * for the resolution-count assertions above.
     */
    private fun sliceTestController(
        siteTypes: List<CampsiteKind>,
        providers: List<AvailabilityProvider> = listOf(FakeAvailabilityProvider(BookingProvider.RECGOV)),
    ): SliceFixture {
        ctx.cleanCanonicalCatalogFixtures()
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "slice-poi",
                name = "Slice Test CG",
                lon = -119.56,
                lat = 37.74,
                providerRefJson = """{"recgov_id": "232447"}""",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        siteTypes.forEachIndexed { index, kind ->
            ctx.seedCampsite(campgroundId = fixture.catalogId, vendorId = "slice-$index", kind = kind.wire)
        }

        val campsitesRepo = CampsiteRepo(ctx)
        val campgroundRepo = CampgroundRepo(ctx)
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
        val targets =
            CountingTargetResolver(
                DbAvailabilityTargetResolver(
                    poiRepo = PoiRepo(ctx),
                    campsitesRepo = campsitesRepo,
                    campgroundRepo = campgroundRepo,
                    availabilityProviders = providers,
                    dateResolver = dateResolver,
                    pollerRepo = AvailabilityPollerRepo(ctx),
                ),
            )

        val controller =
            CampsiteAvailabilityController(
                campgroundRepo = campgroundRepo,
                campsitesRepo = campsitesRepo,
                catalogService = CampsiteCatalogService(DbRefResolver(RefLinkRepo(ctx)), campsitesRepo, targets),
                availabilityService =
                    CampsiteAvailabilityService(
                        availabilityProviders = providers,
                        dateResolver = dateResolver,
                        failoverFetcher = CannedBatchFetcher(),
                        bookingHorizons = BookingHorizonResolver(providers, dateResolver),
                        availabilityRepo = null,
                    ),
                dateResolver = dateResolver,
                watchCapabilityService =
                    WatchCapabilityService(
                        availabilityTargets = targets,
                        bookingTargets = AvailabilityBookingTargetResolver(BookingAdapterRegistry(emptyList())),
                    ),
            )
        val earliest = dateResolver.contextForPoi(TEST_POI_ID).earliestDate
        val start = earliest.plusDays(WINDOW_START_OFFSET_DAYS)
        return SliceFixture(
            controller = controller,
            targets = targets,
            earliestDate = earliest,
            startDate = start,
            endDate = start.plusDays(WINDOW_LENGTH_DAYS),
        )
    }
}

private data class SliceFixture(
    val controller: CampsiteAvailabilityController,
    val targets: CountingTargetResolver,
    val earliestDate: LocalDate,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/** Counts per-campsite target resolutions, each of which is three DB round trips. */
private class CountingTargetResolver(
    private val delegate: AvailabilityTargetResolver,
) : AvailabilityTargetResolver {
    var campsiteResolves = 0
        private set

    override fun resolve(campsite: Campsite): ResolvedAvailabilityTarget? {
        campsiteResolves++
        return delegate.resolve(campsite)
    }

    override fun resolve(poller: AvailabilityPollerRepo.Poller): PollerFetchPlan? = delegate.resolve(poller)
}

/**
 * Failover fetcher stub: skips the real upstream call and answers every
 * requested campsite AVAILABLE for the start of the fetch window, so the
 * slice's batch-shaping is observable without any provider I/O.
 */
private class CannedBatchFetcher : FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))) {
    override suspend fun fetch(
        providers: List<AvailabilityProvider>,
        campground: Campground,
        campsites: List<Campsite>,
        window: ResolvedDateWindow,
    ): FailoverResult {
        val observedAt = Instant.parse("2026-07-30T12:00:00Z")
        val batch =
            AvailabilityObservationBatch(
                provider = providers.first().id.id,
                startDate = window.startDate,
                endDate = window.endDate,
                observations =
                    campsites.map { campsite ->
                        CampsiteDayObservation(campsite.id, window.startDate, observedAt, AvailabilityStatus.AVAILABLE)
                    },
                cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
            )
        return FailoverResult(batch = batch, servedBy = providers.first().id, attempts = emptyList())
    }
}
