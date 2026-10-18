package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheConfig
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.testCampgroundReadPath
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampgroundObservations
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityRepo
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
private val aspiraTtlOverride: Duration = Duration.ofMinutes(15)

// Wide enough that any row this suite writes counts as fresh, forcing the cache path.
private val alwaysFreshTtl: Duration = Duration.ofDays(3650)

class CampgroundAvailabilityReaderTest : SharedDbTest() {
    private lateinit var campground: Campground
    private lateinit var campsites: List<Campsite>
    private var poiId = 0L

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
        poiId = fixture.poiId
        campground = CampgroundRepo(ctx).findByPoi(fixture.poiId)!!
        campsites = CampsiteRepo(ctx).findByCampground(fixture.catalogId)
    }

    private fun dateContext() = PoiDateContext(timeZone = ZoneId.of("UTC"), earliestDate = earliestBookable)

    private fun reader(
        providers: List<AvailabilityProvider>,
        fetcher: FailoverAvailabilityFetcher = CapturingFetcher(),
        clock: Clock = Clock.systemUTC(),
        snapshotFreshnessTtl: (AvailabilityProvider) -> Duration = { defaultSnapshotFreshnessTtl(it.id) },
        availabilityRepo: AvailabilityRepo? = null,
    ): CampgroundAvailabilityReader =
        testCampgroundReadPath(
            providers = providers,
            dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
            failoverFetcher = fetcher,
            availabilityRepo = availabilityRepo,
            clock = clock,
            snapshotFreshnessTtl = snapshotFreshnessTtl,
        ).reader

    private suspend fun CampgroundAvailabilityReader.readCampground(
        named: List<Campsite> = campsites,
        campgroundWide: List<Campsite> = campsites,
    ): CampgroundWindow =
        read(
            poiId = poiId,
            campground = campground,
            campgroundCampsites = campgroundWide,
            campsites = named,
            dateContext = dateContext(),
            startDate = null,
            endDate = null,
            freshAtOrAfter = null,
        )

    @Test
    fun `a cache hit still carries the provider's parent ref as the campground ref`() {
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val reader =
            reader(
                providers = listOf(recgov),
                snapshotFreshnessTtl = { alwaysFreshTtl },
                availabilityRepo = AvailabilityRepo(ctx),
            )

        val window =
            runBlocking {
                reader.readCampground()
                reader.readCampground()
            }

        assertEquals(true, window.cache?.hit)
        assertEquals(recgov.parentRefFor(campground), window.campgroundRef)
    }

    @Test
    fun `the response names the provider failover served, not the one picked first`() {
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val fetcher = CapturingFetcher(servedBy = { BookingProvider.CAMPFLARE })

        val window = runBlocking { reader(listOf(recgov), fetcher).readCampground() }

        assertEquals(BookingProvider.CAMPFLARE, window.provider)
    }

    @Test
    fun `a read served from the store names the provider the policy picked`() {
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val fetcher = CapturingFetcher(servedBy = { BookingProvider.CAMPFLARE })
        val reader =
            reader(
                providers = listOf(recgov),
                fetcher = fetcher,
                snapshotFreshnessTtl = { alwaysFreshTtl },
                availabilityRepo = AvailabilityRepo(ctx),
            )

        val window =
            runBlocking {
                reader.readCampground()
                reader.readCampground()
            }

        assertEquals(true, window.cache?.hit)
        assertEquals(BookingProvider.RECGOV, window.provider)
    }

    @Test
    fun `a campground-wide fetch is narrowed to the campsites the request named`() {
        val unnamedCampsite = campsites.first().id + 1
        val fetcher = CapturingFetcher(alsoReportsCampsite = unnamedCampsite)

        val window =
            runBlocking { reader(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV)), fetcher).readCampground() }

        assertEquals(listOf(campsites.first().id), window.streams.map { it.campsiteId })
    }

    @Test
    fun `the model's streams cover the campsites the read answers for, and no others`() {
        val named = campsites.first()
        val unnamed = campsites.first().copy(id = named.id + 1)
        val fetcher = CapturingFetcher(alsoReportsCampsite = unnamed.id)

        val window =
            runBlocking {
                reader(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV)), fetcher)
                    .readCampground(named = listOf(named), campgroundWide = listOf(named, unnamed))
            }

        assertEquals(listOf(named.id, unnamed.id), fetcher.capturedCampgroundCampsiteIds, "the fetch covered the campground")
        assertEquals(listOf(named.id), window.campsites.map { it.id })
        assertEquals(listOf(named.id, unnamed.id), window.campgroundCampsites.map { it.id })
        assertEquals(listOf(named.id), window.streams.map { it.campsiteId })
    }

    @Test
    fun `dispatches to the first provider that supports the campground`() {
        val aspira = FakeAvailabilityProvider(BookingProvider.ASPIRA)
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val fetcher = CapturingFetcher()

        val window = runBlocking { reader(listOf(aspira, recgov), fetcher).readCampground() }

        assertEquals(listOf<AvailabilityProvider>(recgov), fetcher.capturedProviders)
        assertEquals(ResolvedDateWindow(earliestBookable, earliestBookable.plusDays(DEFAULT_WINDOW_DAYS)), window.window)
        assertEquals(
            ResolvedDateWindow(earliestBookable, earliestBookable.plusDays(WIDE_WINDOW_DAYS)),
            fetcher.capturedWindow,
        )
    }

    @Test
    fun `slices the fetched cells down to the requested target window`() {
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)

        val window = runBlocking { reader(listOf(recgov)).readCampground() }

        val stream = window.streams.single()
        assertEquals(DEFAULT_WINDOW_DAYS.toInt(), stream.statuses.size)
        assertEquals(AvailabilityStatus.AVAILABLE, stream.statuses.first())
        assertEquals(setOf(AvailabilityStatus.UNKNOWN), stream.statuses.drop(1).toSet())
        assertEquals(false, window.cache?.hit)
    }

    @Test
    fun `throws UnknownCampground when no provider supports the campground`() {
        val aspiraOnly = FakeAvailabilityProvider(BookingProvider.ASPIRA)

        val error =
            assertFailsWith<AvailabilityServiceError> {
                runBlocking { reader(listOf(aspiraOnly)).readCampground() }
            }
        assertSame(AvailabilityServiceError.UnknownCampground, error)
    }

    @Test
    fun `snapshot freshness TTL is selected for the dispatched provider`() {
        val clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val ttl = Duration.ofMinutes(5)
        var askedFor: AvailabilityProvider? = null

        val window =
            runBlocking {
                reader(
                    providers = listOf(recgov),
                    clock = clock,
                    snapshotFreshnessTtl = { provider ->
                        askedFor = provider
                        ttl
                    },
                ).readCampground()
            }

        assertSame(recgov, askedFor)
        assertEquals(ttl.seconds, window.cache?.ttlSeconds)
    }

    @Test
    fun `the injected clock is the same clock the loader measures ttl against`() {
        val clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val ttl = Duration.ofMinutes(5)

        val window =
            runBlocking {
                reader(providers = listOf(recgov), clock = clock, snapshotFreshnessTtl = { ttl }).readCampground()
            }

        assertEquals(ttl.seconds, window.cache?.ttlSeconds)
    }

    @Test
    fun `default snapshot freshness TTL is the provider's cache entity default`() {
        for (provider in BookingProvider.entries) {
            assertEquals(
                ApiCacheEntity.availability(provider).defaultTtl,
                defaultSnapshotFreshnessTtl(provider),
                provider.id,
            )
        }
    }

    @Test
    fun `configured snapshot freshness TTL reads the per-provider cache override`() {
        val cache =
            ApiCacheConfig(
                ttlByEntity = mapOf(ApiCacheEntity.availability(BookingProvider.ASPIRA) to aspiraTtlOverride),
            )
        val snapshotFreshnessTtl = configuredSnapshotFreshnessTtl(cache)

        assertEquals(aspiraTtlOverride, snapshotFreshnessTtl(FakeAvailabilityProvider(BookingProvider.ASPIRA)))
        assertEquals(
            ApiCacheEntity.availability(BookingProvider.RECGOV).defaultTtl,
            snapshotFreshnessTtl(FakeAvailabilityProvider(BookingProvider.RECGOV)),
        )
    }

    /**
     * Failover fetcher stub: captures what the reader hands it and answers with
     * a two-day batch (one day inside the default 7-day target window, one far
     * outside it) so slicing behaviour is observable.
     */
    private inner class CapturingFetcher(
        private val servedBy: (List<AvailabilityProvider>) -> BookingProvider = { it.first().id },
        private val alsoReportsCampsite: Long? = null,
    ) : FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))) {
        var capturedProviders: List<AvailabilityProvider>? = null
        var capturedWindow: ResolvedDateWindow? = null
        var capturedCampgroundCampsiteIds: List<Long>? = null

        override suspend fun fetch(
            providers: List<AvailabilityProvider>,
            campground: Campground,
            campsites: List<Campsite>,
            campgroundCampsites: List<Campsite>,
            window: ResolvedDateWindow,
        ): FailoverResult {
            capturedProviders = providers
            capturedWindow = window
            capturedCampgroundCampsiteIds = campgroundCampsites.map { it.id }
            val campsiteId = campsites.first().id
            val observedAt = Instant.parse("2026-07-30T12:00:00Z")
            val observations =
                CampgroundObservations(
                    campgroundRef = null,
                    observations =
                        listOfNotNull(
                            CampsiteDayObservation(campsiteId, window.startDate, observedAt, AvailabilityStatus.AVAILABLE),
                            alsoReportsCampsite?.let {
                                CampsiteDayObservation(it, window.startDate, observedAt, AvailabilityStatus.AVAILABLE)
                            },
                            CampsiteDayObservation(
                                campsiteId,
                                window.startDate.plusDays(DEFAULT_WINDOW_DAYS + 1),
                                observedAt,
                                AvailabilityStatus.AVAILABLE,
                            ),
                        ),
                )
            return FailoverResult(observations = observations, servedBy = servedBy(providers), attempts = emptyList())
        }
    }
}
