package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.campgroundBatch
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_POI_ID = 1L
private const val WINDOW_START_OFFSET_DAYS = 1L
private const val WINDOW_LENGTH_DAYS = 7L
private val fixedNow: Instant = Instant.parse("2026-07-30T12:00:00Z")
private val freshnessTtl: Duration = Duration.ofMinutes(30)

/**
 * What the base-unit cache buys and what it must not confuse: one upstream call
 * per campground whatever a request filtered to, answers narrowed above the
 * port, and a season block that reads the same whether it was just fetched or
 * served from the store.
 */
class CampgroundCellCacheTest : SharedDbTest() {
    @Test
    fun `a filtered check then an unfiltered one over the same window is one provider call`() {
        val fixture = checkFixture()

        val filtered = fixture.slice(siteTypes = listOf(CampsiteKind.TENT))
        val unfiltered = fixture.slice(siteTypes = emptyList())

        assertEquals(1, fixture.provider.calls.size, "the unfiltered check called the vendor again")
        // The one call was about the campground, not about the tent filter that
        // set it off.
        assertEquals(
            fixture.allCampsiteIds,
            fixture.provider.calls
                .single()
                .campsites
                .mapTo(mutableSetOf()) { it.id },
        )

        assertEquals(2, filtered.campsites.size)
        assertFalse(filtered.batch!!.cacheBlock.hit, "the first check had nothing stored to serve")
        assertEquals(4, unfiltered.campsites.size)
        assertTrue(unfiltered.batch!!.cacheBlock.hit, "the whole campground's cells were already stored")
        assertEquals(fixture.allCampsiteIds, unfiltered.batch!!.observations.mapNotNullTo(mutableSetOf()) { it.campsiteId })
    }

    @Test
    fun `a filtered check answers only about the campsites it asked for`() {
        val fixture = checkFixture()

        val filtered = fixture.slice(siteTypes = listOf(CampsiteKind.TENT))

        // The call covered four campsites; the answer is about two.
        assertEquals(
            filtered.campsites.mapTo(mutableSetOf()) { it.id },
            filtered.batch!!.observations.mapNotNullTo(mutableSetOf()) { it.campsiteId },
        )
    }

    @Test
    fun `a closed window keeps its reopen date when the answer comes from the store`() {
        // The reopen date used to be a by-product of the call that fetched: the
        // same request twice said `closed_for_season` with a date and then
        // without one. It is read from stored cells now, so a cache hit says
        // what the fetch said.
        val fixture = checkFixture(status = AvailabilityStatus.CLOSED, reopensAfterWindow = true)

        val fetched = fixture.fusedWeek(siteTypes = emptyList())
        val cached = fixture.fusedWeek(siteTypes = emptyList())

        assertEquals(1, fixture.provider.calls.size, "the second read should have been served from the store")
        assertNotNull(fetched.season, "a closed week with a bookable date ahead of it has a reopen date")
        assertEquals(fetched.season, cached.season)
        assertEquals(fixture.reopenDate.toString(), cached.season?.reopensOn)
    }

    @Test
    fun `a filtered check is not told when another kind reopens`() {
        // Cabins shut for the winter, tents open later in the fetch window: the
        // cabin caller must be told nothing rather than told the tents' date.
        val fixture = checkFixture(status = AvailabilityStatus.CLOSED, reopensAfterWindow = true, reopeningKind = CampsiteKind.TENT)

        val cabins = fixture.fusedWeek(siteTypes = listOf(CampsiteKind.CABIN))
        val tents = fixture.fusedWeek(siteTypes = listOf(CampsiteKind.TENT))

        assertNull(cabins.season, "the tents reopening says nothing about the cabins")
        assertEquals(fixture.reopenDate.toString(), tents.season?.reopensOn)
    }

    private fun checkFixture(
        status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
        reopensAfterWindow: Boolean = false,
        reopeningKind: CampsiteKind? = null,
    ): CheckFixture {
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
            kinds.mapIndexed { index, kind ->
                ctx.seedCampsite(campgroundId = catalog.catalogId, vendorId = "one-call-$index", kind = kind.wire)
            }
        val kindByCampsiteId = campsiteIds.zip(kinds).toMap()
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
        val windowStart = dateResolver.contextForPoi(TEST_POI_ID).earliestDate.plusDays(WINDOW_START_OFFSET_DAYS)
        // Past the target window, inside the wider window the fetch covers:
        // exactly the reach a reopen date is for.
        val reopenDate = windowStart.plusDays(WINDOW_LENGTH_DAYS + 3)

        val provider =
            FakeAvailabilityProvider(
                BookingProvider.RECGOV,
                onAvailability = { call ->
                    val base = campgroundBatch(call, BookingProvider.RECGOV, status = status, observedAt = fixedNow)
                    if (!reopensAfterWindow) {
                        base
                    } else {
                        base.copy(
                            observations =
                                base.observations.map { observation ->
                                    val reopens =
                                        observation.date == reopenDate &&
                                            (reopeningKind == null || kindByCampsiteId[observation.campsiteId] == reopeningKind)
                                    if (reopens) observation.copy(status = AvailabilityStatus.AVAILABLE) else observation
                                },
                        )
                    }
                },
            )
        val providers: List<AvailabilityProvider> = listOf(provider)

        val campsitesRepo = CampsiteRepo(ctx)
        val campgroundRepo = CampgroundRepo(ctx)
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
                        // The real fetcher, so the counted call is the one the
                        // port actually makes.
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
        return CheckFixture(
            controller = controller,
            provider = provider,
            allCampsiteIds = campsiteIds.toMutableSet(),
            startDate = windowStart,
            endDate = windowStart.plusDays(WINDOW_LENGTH_DAYS),
            reopenDate = reopenDate,
        )
    }
}

private class CheckFixture(
    val controller: CampsiteAvailabilityController,
    val provider: FakeAvailabilityProvider,
    val allCampsiteIds: Set<Long>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reopenDate: LocalDate,
) {
    fun slice(siteTypes: List<CampsiteKind>): PoiAvailabilitySlice =
        runBlocking {
            controller.poiAvailabilitySlice(
                poiId = TEST_POI_ID,
                siteTypes = siteTypes,
                startDate = startDate,
                endDate = endDate,
            )
        }

    fun fusedWeek(siteTypes: List<CampsiteKind>): FusedWindow {
        val slice = slice(siteTypes)
        return fusePoiWindow(slice, slice.earliestDate)
    }
}
