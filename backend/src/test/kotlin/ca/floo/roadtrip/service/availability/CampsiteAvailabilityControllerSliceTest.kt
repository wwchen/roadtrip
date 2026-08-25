package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TEST_POI_ID = 1L

class CampsiteAvailabilityControllerSliceTest : SharedDbTest() {
    @Test
    fun `slice carries the resolved window and the filtered campsites`() {
        val controller = sliceTestController(siteTypes = listOf("tent", "rv"))

        val slice =
            runBlocking {
                controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf("tent"),
                    startDate = LocalDate.of(2026, 9, 4),
                    endDate = LocalDate.of(2026, 9, 11),
                )
            }

        assertEquals(LocalDate.of(2026, 9, 4), slice.startDate)
        assertEquals(LocalDate.of(2026, 9, 11), slice.endDate)
        assertEquals(2, slice.allCampsites.size)
        assertEquals(1, slice.campsites.size)
        assertNotNull(slice.batch)
    }

    @Test
    fun `slice has a null batch when no campsite matches the site type filter`() {
        val controller = sliceTestController(siteTypes = listOf("tent"))

        val slice =
            runBlocking {
                controller.poiAvailabilitySlice(
                    poiId = TEST_POI_ID,
                    siteTypes = listOf("cabin"),
                    startDate = LocalDate.of(2026, 9, 4),
                    endDate = LocalDate.of(2026, 9, 11),
                )
            }

        assertEquals(0, slice.campsites.size)
        assertNull(slice.batch)
    }

    /**
     * Builds a controller over a fresh POI/campground with one seeded campsite
     * per entry in [siteTypes]. Mirrors the fake repos/services
     * `CampsiteAvailabilityServiceTest` builds: real repos over the shared test
     * DB, a [SliceFakeAvailabilityProvider] standing in for rec.gov, and a
     * failover fetcher stubbed to answer with a canned batch instead of
     * calling out. `catalogService` and `watchCapabilityService` are wired
     * with real implementations since `poiAvailabilitySlice` never exercises
     * them but the controller's constructor still requires them.
     */
    private fun sliceTestController(siteTypes: List<String>): CampsiteAvailabilityController {
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
            ctx.seedCampsite(campgroundId = fixture.catalogId, vendorId = "slice-$index", kind = kind)
        }

        val campsitesRepo = CampsiteRepo(ctx)
        val campgroundRepo = CampgroundRepo(ctx)
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
        val providers = listOf(SliceFakeAvailabilityProvider(BookingProvider.RECGOV))
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = campgroundRepo,
                availabilityProviders = providers,
                dateResolver = dateResolver,
                pollerRepo = AvailabilityPollerRepo(ctx),
            )

        return CampsiteAvailabilityController(
            campgroundRepo = campgroundRepo,
            campsitesRepo = campsitesRepo,
            catalogService = CampsiteCatalogService(DbRefResolver(RefLinkRepo(ctx)), campsitesRepo, targets),
            availabilityService =
                CampsiteAvailabilityService(
                    availabilityProviders = providers,
                    dateResolver = dateResolver,
                    failoverFetcher = CannedBatchFetcher(),
                    availabilityRepo = null,
                ),
            dateResolver = dateResolver,
            watchCapabilityService =
                WatchCapabilityService(
                    availabilityTargets = targets,
                    bookingTargets = AvailabilityBookingTargetResolver(BookingAdapterRegistry(emptyList())),
                ),
        )
    }
}

private class SliceFakeAvailabilityProvider(
    override val id: BookingProvider,
) : AvailabilityProvider {
    override val capabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = 180,
            maxPollWindowDays = 60,
        )

    override fun isEnabled(): Boolean = true

    override suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used: the canned fetcher answers instead")
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
