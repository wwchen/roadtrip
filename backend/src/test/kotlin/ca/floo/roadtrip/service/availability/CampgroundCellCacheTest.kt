package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.campgroundObservations
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testCampgroundReadPath
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_POI_ID = 1L
private const val WINDOW_START_OFFSET_DAYS = 1L
private const val WINDOW_LENGTH_DAYS = 7L
private val fixedNow: Instant = Instant.parse("2026-07-30T12:00:00Z")
private val freshnessTtl: Duration = Duration.ofMinutes(30)

class CampgroundCellCacheTest : SharedDbTest() {
    @Test
    fun `a filtered check then an unfiltered one over the same window is one provider call`() {
        val fixture = checkFixture()

        val filtered = fixture.window(siteTypes = listOf(CampsiteKind.TENT))
        val unfiltered = fixture.window(siteTypes = emptyList())

        assertEquals(1, fixture.provider.calls.size, "the unfiltered check called the vendor again")
        assertEquals(
            fixture.allCampsiteIds,
            fixture.provider.calls
                .single()
                .campsites
                .mapTo(mutableSetOf()) { it.id },
        )

        assertEquals(2, filtered.campsites.size)
        assertEquals(false, filtered.cache?.hit, "the first check had nothing stored to serve")
        assertEquals(4, unfiltered.campsites.size)
        assertEquals(true, unfiltered.cache?.hit, "the whole campground's cells were already stored")
        assertEquals(fixture.allCampsiteIds, unfiltered.streams.mapTo(mutableSetOf()) { it.campsiteId })
        assertTrue(unfiltered.streams.all { stream -> stream.statuses.all { it == AvailabilityStatus.AVAILABLE } })
    }

    @Test
    fun `a filtered check answers only about the campsites it asked for`() {
        val fixture = checkFixture()

        val filtered = fixture.window(siteTypes = listOf(CampsiteKind.TENT))

        assertEquals(1, fixture.provider.calls.size)
        assertEquals(
            fixture.allCampsiteIds,
            fixture.provider.calls
                .single()
                .campsites
                .mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(
            filtered.campsites.mapTo(mutableSetOf()) { it.id },
            filtered.streams.mapTo(mutableSetOf()) { it.campsiteId },
        )
    }

    @Test
    fun `a closed window keeps its reopen date when the answer comes from the store`() {
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
        val fixture = checkFixture(status = AvailabilityStatus.CLOSED, reopensAfterWindow = true, reopeningKind = CampsiteKind.TENT)

        val cabins = fixture.fusedWeek(siteTypes = listOf(CampsiteKind.CABIN))
        val tents = fixture.fusedWeek(siteTypes = listOf(CampsiteKind.TENT))

        assertNull(cabins.season, "the tents reopening says nothing about the cabins")
        assertEquals(fixture.reopenDate.toString(), tents.season?.reopensOn)
    }

    @Test
    fun `a full window looks up no reopen date`() {
        val fixture = checkFixture(status = AvailabilityStatus.RESERVED, reopensAfterWindow = true)

        val window = fixture.window(siteTypes = emptyList())

        assertEquals(emptyMap(), window.reopensOnByCampsite)
        assertNull(window.fusedWindow().season)
    }

    @Test
    fun `a closed window stops reporting a reopen date nobody has looked at since`() {
        val fixture = checkFixture(status = AvailabilityStatus.CLOSED, reopensAfterWindow = true)

        val fresh = fixture.fusedWeek(siteTypes = emptyList())
        assertEquals(fixture.reopenDate.toString(), fresh.season?.reopensOn)

        val stale = fixture.fusedWeek(siteTypes = emptyList(), freshAtOrAfter = fixedNow.plusSeconds(1))
        assertNull(stale.season, "a cell observed before the cutoff is not a reopening")
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
        val reopenDate = windowStart.plusDays(WINDOW_LENGTH_DAYS + 3)

        val provider =
            FakeAvailabilityProvider(
                BookingProvider.RECGOV,
                onAvailability = { call ->
                    val base = campgroundObservations(call, status = status, observedAt = fixedNow)
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
        val readPath =
            testCampgroundReadPath(
                providers = providers,
                dateResolver = dateResolver,
                failoverFetcher = FailoverAvailabilityFetcher(ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))),
                availabilityRepo = AvailabilityRepo(ctx),
                clock = clock,
                snapshotFreshnessTtl = { freshnessTtl },
            )
        val controller =
            CampsiteAvailabilityController(
                campgroundRepo = campgroundRepo,
                campsitesRepo = campsitesRepo,
                catalogService = testCampsiteCatalogService(ctx, campsitesRepo, targets),
                policy = readPath.policy,
                reader = readPath.reader,
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
    fun window(
        siteTypes: List<CampsiteKind>,
        freshAtOrAfter: Instant? = null,
    ): CampgroundWindow =
        runBlocking {
            controller.campgroundWindow(
                poiId = TEST_POI_ID,
                siteTypes = siteTypes,
                startDate = startDate,
                endDate = endDate,
                freshAtOrAfter = freshAtOrAfter,
            )
        }

    fun fusedWeek(
        siteTypes: List<CampsiteKind>,
        freshAtOrAfter: Instant? = null,
    ): FusedWindow = window(siteTypes, freshAtOrAfter).fusedWindow()
}
