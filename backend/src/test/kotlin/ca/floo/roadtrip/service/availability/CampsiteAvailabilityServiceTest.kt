package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private val earliestBookable: LocalDate = LocalDate.of(2026, 8, 1)
private const val DEFAULT_WINDOW_DAYS = 7L
private const val WIDE_WINDOW_DAYS = 60L

class CampsiteAvailabilityServiceTest : SharedDbTest() {
    private lateinit var campground: Campground
    private lateinit var campsites: List<Campsite>

    @BeforeEach
    fun seed() {
        ctx.cleanCanonicalCatalogFixtures()
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "svc-poi",
                name = "Service Test CG",
                lon = -119.56,
                lat = 37.74,
                providerRefJson = """{"recgov_id": "232447"}""",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
            )
        ctx.seedCampsite(campgroundId = fixture.catalogId, vendorId = "svc-100")
        campground = CampgroundRepo(ctx).findByPoi(fixture.poiId)!!
        campsites = CampsiteRepo(ctx).findByCampground(fixture.catalogId)
    }

    private fun dateContext() = PoiDateContext(timeZone = ZoneId.of("UTC"), earliestDate = earliestBookable)

    private fun service(
        providers: List<AvailabilityProvider>,
        fetcher: FailoverAvailabilityFetcher = CapturingFetcher(),
        clock: Clock = Clock.systemUTC(),
        snapshotFreshnessTtl: (AvailabilityProvider) -> Duration = { defaultSnapshotFreshnessTtl(it.id) },
    ) = CampsiteAvailabilityService(
        availabilityProviders = providers,
        dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
        failoverFetcher = fetcher,
        availabilityRepo = null,
        clock = clock,
        snapshotFreshnessTtl = snapshotFreshnessTtl,
    )

    @Test
    fun `dispatches to the first provider that supports the campground`() {
        val aspira = FakeAvailabilityProvider(BookingProvider.ASPIRA)
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val fetcher = CapturingFetcher()

        val result =
            runBlocking {
                service(listOf(aspira, recgov), fetcher).fetchAvailability(
                    campground = campground,
                    campsites = campsites,
                    startDate = null,
                    endDate = null,
                    dateContext = dateContext(),
                )
            }

        // Only the supporting provider reaches the failover fetcher; the aspira
        // adapter (wrong booking provider for this campground) is filtered out.
        assertEquals(listOf<AvailabilityProvider>(recgov), fetcher.capturedProviders)
        // Target window: default 7 days anchored at the earliest bookable date.
        assertEquals(earliestBookable, result.startDate)
        assertEquals(earliestBookable.plusDays(DEFAULT_WINDOW_DAYS), result.endDate)
        // Fetch window: the widest single call the vendor allows (60 days).
        assertEquals(
            ResolvedDateWindow(earliestBookable, earliestBookable.plusDays(WIDE_WINDOW_DAYS)),
            fetcher.capturedWindow,
        )
    }

    @Test
    fun `slices the fetched batch down to the requested target window`() {
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val fetcher = CapturingFetcher()

        val result =
            runBlocking {
                service(listOf(recgov), fetcher).fetchAvailability(
                    campground = campground,
                    campsites = campsites,
                    startDate = null,
                    endDate = null,
                    dateContext = dateContext(),
                )
            }

        // The fake fetcher reports one in-window and one out-of-window day; only
        // the in-window observation survives the slice to the 7-day target.
        assertEquals(listOf(earliestBookable), result.batch.observations.map { it.date })
        assertEquals(false, result.batch.cacheBlock.hit)
    }

    @Test
    fun `throws UnknownCampground when no provider supports the campground`() {
        val aspiraOnly = FakeAvailabilityProvider(BookingProvider.ASPIRA)

        val error =
            assertFailsWith<AvailabilityServiceError> {
                runBlocking {
                    service(listOf(aspiraOnly)).fetchAvailability(
                        campground = campground,
                        campsites = campsites,
                        startDate = null,
                        endDate = null,
                        dateContext = dateContext(),
                    )
                }
            }
        assertSame(AvailabilityServiceError.UnknownCampground, error)
    }

    @Test
    fun `snapshot freshness TTL is selected for the dispatched provider`() {
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val ttl = Duration.ofMinutes(5)
        var askedFor: AvailabilityProvider? = null

        val result =
            runBlocking {
                service(
                    providers = listOf(recgov),
                    snapshotFreshnessTtl = { provider ->
                        askedFor = provider
                        ttl
                    },
                ).fetchAvailability(
                    campground = campground,
                    campsites = campsites,
                    startDate = null,
                    endDate = null,
                    dateContext = dateContext(),
                )
            }

        assertSame(recgov, askedFor)
        assertEquals(ttl.seconds, result.batch.cacheBlock.ttlSeconds)
    }

    @Test
    fun `the service's injected clock is the same clock its loader measures ttl against`() {
        // Fixed far from the real system clock: if the loader fell back to its own
        // Clock.systemUTC() instead of the one threaded through from the service,
        // ttlSeconds would come back as a multi-year duration, not exactly `ttl`.
        val fixedNow = Instant.parse("2020-01-01T00:00:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val ttl = Duration.ofMinutes(5)

        val result =
            runBlocking {
                service(
                    providers = listOf(recgov),
                    clock = clock,
                    snapshotFreshnessTtl = { ttl },
                ).fetchAvailability(
                    campground = campground,
                    campsites = campsites,
                    startDate = null,
                    endDate = null,
                    dateContext = dateContext(),
                )
            }

        assertEquals(ttl.seconds, result.batch.cacheBlock.ttlSeconds)
    }

    @Test
    fun `default snapshot freshness TTL maps each provider to its cache entity TTL`() {
        assertEquals(ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl, defaultSnapshotFreshnessTtl(BookingProvider.RECGOV))
        assertEquals(ApiCacheEntity.CAMPFLARE_AVAILABILITY.defaultTtl, defaultSnapshotFreshnessTtl(BookingProvider.CAMPFLARE))
        assertEquals(ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl, defaultSnapshotFreshnessTtl(BookingProvider.ASPIRA))
        assertEquals(
            ApiCacheEntity.RESERVEAMERICA_AVAILABILITY.defaultTtl,
            defaultSnapshotFreshnessTtl(BookingProvider.RESERVEAMERICA),
        )
        assertEquals(
            ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY.defaultTtl,
            defaultSnapshotFreshnessTtl(BookingProvider.RESERVECALIFORNIA),
        )
    }

    /**
     * Failover fetcher stub: captures what the service hands it and answers with
     * a two-day batch (one day inside the default 7-day target window, one far
     * outside it) so slicing behaviour is observable.
     */
    private inner class CapturingFetcher :
        FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))) {
        var capturedProviders: List<AvailabilityProvider>? = null
        var capturedWindow: ResolvedDateWindow? = null

        override suspend fun fetch(
            providers: List<AvailabilityProvider>,
            campground: Campground,
            campsites: List<Campsite>,
            window: ResolvedDateWindow,
        ): FailoverResult {
            capturedProviders = providers
            capturedWindow = window
            val campsiteId = campsites.first().id
            val observedAt = Instant.parse("2026-07-30T12:00:00Z")
            val batch =
                AvailabilityObservationBatch(
                    provider = providers.first().id.id,
                    startDate = window.startDate,
                    endDate = window.endDate,
                    observations =
                        listOf(
                            CampsiteDayObservation(campsiteId, window.startDate, observedAt, AvailabilityStatus.AVAILABLE),
                            CampsiteDayObservation(
                                campsiteId,
                                window.startDate.plusDays(DEFAULT_WINDOW_DAYS + 1),
                                observedAt,
                                AvailabilityStatus.AVAILABLE,
                            ),
                        ),
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
                )
            return FailoverResult(batch = batch, servedBy = providers.first().id, attempts = emptyList())
        }
    }
}

private class FakeAvailabilityProvider(
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
    ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used: the capturing fetcher answers instead")
}
