package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.AvailabilityCacheStore
import ca.floo.roadtrip.repo.AvailabilityCellRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ReservableAvailabilityComposer.availabilityFor] and its
 * delegation to [CatalogAvailabilityBatcher] + the snapshot cache wrapper. Uses
 * in-memory fakes for the target resolver, reservation provider, and snapshot
 * store — consistent with [CatalogAvailabilityBatcherTest]; no DB required.
 */
class ReservableAvailabilityComposerTest {
    // A fixed future earliest date keeps the default window deterministic and
    // clear of AvailabilityDateResolver's earliest-date guard.
    private val earliest = LocalDate.parse("2026-08-01")
    private val longTtl = Duration.ofHours(1)

    @Test
    fun `repeat call hits the snapshot cache instead of re-fetching upstream`() =
        runBlocking {
            val provider = fakeProvider()
            val ref = ProviderRef.RecGov(recgovId = "232447")
            val target = resolvedTarget("site:recgov:100", dbId = 1L, provider = provider, parentRef = ref)
            val store = FakeCacheStore()
            val composer = composer(listOf(target), store)

            val first = composer.availabilityFor(listOf(target.reservable), null, null)
            assertEquals(1, provider.catalogCalls, "cache miss should fetch upstream exactly once")
            assertEquals(1, store.recordCalls, "cache miss should persist a snapshot")
            assertFalse(first.single().cache.hit, "first read is a miss")

            val second = composer.availabilityFor(listOf(target.reservable), null, null)
            assertEquals(1, provider.catalogCalls, "repeat read must be served from cache, not re-fetched")
            assertEquals(1, store.recordCalls, "cache hit should not write again")
            assertTrue(second.single().cache.hit, "second read is a cache hit")
        }

    @Test
    fun `multi-rid request spanning distinct groups splits results per rid`() =
        runBlocking {
            // Two rids under different parent refs → two fetch groups. Each rid's
            // response must carry only its own observations, keyed to its rid.
            val provider =
                fakeProvider { req ->
                    // Group A (site 100) is AVAILABLE; group B (site 200) is RESERVED.
                    val status =
                        if (req.reservables.single().rid ==
                            "site:recgov:100"
                        ) {
                            AvailabilityStatus.AVAILABLE
                        } else {
                            AvailabilityStatus.RESERVED
                        }
                    batchFor(req, status)
                }
            val targetA = resolvedTarget("site:recgov:100", dbId = 1L, provider = provider, parentRef = ProviderRef.RecGov("100"))
            val targetB = resolvedTarget("site:recgov:200", dbId = 2L, provider = provider, parentRef = ProviderRef.RecGov("200"))
            val composer = composer(listOf(targetA, targetB), FakeCacheStore())

            // Request order is B then A; results must come back in the requested order.
            val results = composer.availabilityFor(listOf(targetB.reservable, targetA.reservable), null, null)

            assertEquals(2, provider.catalogCalls, "distinct groups fetch independently")
            assertEquals(2, results.size)
            assertEquals("site:recgov:200", results[0].reservableId)
            assertEquals("site:recgov:100", results[1].reservableId)
            // A's window is fully AVAILABLE; B's is fully RESERVED.
            assertTrue(results[1].availability.all { it.status == AvailabilityStatus.AVAILABLE })
            assertTrue(results[0].availability.all { it.status == AvailabilityStatus.RESERVED })
        }

    @Test
    fun `bad date window from the date resolver propagates out of availabilityFor`() =
        runBlocking {
            val provider = fakeProvider()
            val target = resolvedTarget("site:recgov:100", dbId = 1L, provider = provider, parentRef = ProviderRef.RecGov("100"))
            val composer = composer(listOf(target), FakeCacheStore())

            // startDate before the target's earliest bookable date → StartBeforeEarliest.
            assertFailsWith<AvailabilityServiceError.BadDateWindow.StartBeforeEarliest> {
                composer.availabilityFor(listOf(target.reservable), earliest.minusDays(1), null)
            }
            assertEquals(0, provider.catalogCalls, "an invalid window must short-circuit before any upstream fetch")
        }

    @Test
    fun `a reservable with no resolvable provider propagates UnknownCampground before batching`() =
        runBlocking {
            // The composer resolves each already-loaded reservable via the target
            // resolver; a reservable with no linked provider target resolves to null
            // and must fail the collection before any upstream fetch.
            val composer = composer(targets = emptyList(), cacheStore = FakeCacheStore())
            val error =
                assertFailsWith<AvailabilityServiceError.UnknownCampground> {
                    composer.availabilityFor(listOf(reservable("site:recgov:999")), null, null)
                }
            assertEquals(AvailabilityServiceError.UnknownCampground, error)
        }

    @Test
    fun `provider rate limit on live path rethrows instead of surfacing NotFound`() =
        runBlocking {
            // Regression test: PR 1's CatalogAvailabilityBatcher swallows
            // ReservationProviderError into a classified GroupFetchResult with a
            // null batch. On the live read path that must NOT become a 404 —
            // the old behavior (provider error propagates out of the composer so the
            // route maps it to 503) must be preserved.
            val provider = fakeProvider { throw ReservationProviderError.RateLimited(RuntimeException("429")) }
            val target = resolvedTarget("site:recgov:100", dbId = 1L, provider = provider, parentRef = ProviderRef.RecGov("100"))
            val composer = composer(listOf(target), FakeCacheStore())

            assertFailsWith<ReservationProviderError.RateLimited> {
                composer.availabilityFor(listOf(target.reservable), null, null)
            }
            assertEquals(1, provider.catalogCalls, "provider should be called exactly once before the error propagates")
        }

    // --- fixtures ---

    private fun composer(
        targets: Collection<ResolvedAvailabilityTarget>,
        cacheStore: AvailabilityCacheStore,
    ): ReservableAvailabilityComposer =
        ReservableAvailabilityComposer(
            targets = FakeTargetResolver(targets.associateBy { it.reservable.rid }),
            cacheStore = cacheStore,
            snapshotFreshnessTtl = { longTtl },
        )

    private fun reservable(rid: String): Reservable =
        Reservable(
            id = 0L,
            rid = ReservableId.parse(rid)!!,
            name = null,
            loop = null,
            siteType = null,
            raw = null,
        )

    private fun resolvedTarget(
        rid: String,
        dbId: Long,
        provider: ReservationProvider,
        parentRef: ProviderRef,
        parentPoiId: Long = 1L,
    ): ResolvedAvailabilityTarget =
        ResolvedAvailabilityTarget(
            reservable =
                Reservable(
                    id = dbId,
                    rid = ReservableId.parse(rid)!!,
                    name = null,
                    loop = null,
                    siteType = null,
                    raw = null,
                ),
            provider = provider,
            parentRef = parentRef,
            parentPoiId = parentPoiId,
            dateContext = PoiDateContext(timeZone = ZoneOffset.UTC, earliestDate = earliest),
        )

    private fun fakeProvider(
        onCatalog: (CatalogAvailabilityRequest) -> AvailabilityObservationBatch = { batchFor(it, AvailabilityStatus.AVAILABLE) },
    ): FakeProvider = FakeProvider(onCatalog)

    /** Build a batch that reports [status] for every requested reservable on every day in the window. */
    private fun batchFor(
        req: CatalogAvailabilityRequest,
        status: AvailabilityStatus,
    ): AvailabilityObservationBatch {
        val observedAt = Instant.now()
        val dates = (0 until ChronoUnit.DAYS.between(req.startDate, req.endDate)).map { req.startDate.plusDays(it) }
        val observations =
            req.reservables.flatMap { ref ->
                dates.map { date -> ReservableDayObservation(ref.rid, date, observedAt, status) }
            }
        return AvailabilityObservationBatch(
            provider = "recgov",
            startDate = req.startDate,
            endDate = req.endDate,
            observations = observations,
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        )
    }

    private class FakeTargetResolver(
        private val byRid: Map<ReservableId, ResolvedAvailabilityTarget>,
    ) : AvailabilityTargetResolver {
        override fun requireByRid(rid: ReservableId): ResolvedAvailabilityTarget = byRid[rid] ?: throw AvailabilityServiceError.NotFound

        override fun resolve(reservable: Reservable): ResolvedAvailabilityTarget? = byRid[reservable.rid]
    }

    private class FakeProvider(
        private val onCatalog: (CatalogAvailabilityRequest) -> AvailabilityObservationBatch,
    ) : ReservationProvider {
        var catalogCalls = 0

        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("catalogAvailability is the batched path")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
            catalogCalls++
            return onCatalog(req)
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("composer uses catalogAvailability")
    }

    /**
     * In-memory [AvailabilityCacheStore]: models the availability_cell cube,
     * keeping the current cell per (reservableId, date). `recordCalls` counts
     * fetch persistences so tests can assert a cache hit doesn't re-persist.
     */
    private class FakeCacheStore : AvailabilityCacheStore {
        var recordCalls = 0
        private val cells = linkedMapOf<Pair<Long, LocalDate>, AvailabilityCellRepo.CellObservation>()

        override fun loadLatest(
            reservableIds: List<Long>,
            dates: List<LocalDate>,
        ): List<AvailabilitySnapshotRepo.LatestObservation> {
            val ids = reservableIds.toSet()
            val wanted = dates.toSet()
            return cells.values
                .filter { it.reservableId in ids && it.targetDate in wanted }
                .map { cell ->
                    AvailabilitySnapshotRepo.LatestObservation(
                        reservableId = cell.reservableId,
                        targetDate = cell.targetDate,
                        observedAt = OffsetDateTime.ofInstant(cell.observedAt, ZoneOffset.UTC),
                        status = cell.status,
                        available = cell.status.isOnlineBookable,
                    )
                }
        }

        override fun recordFetched(
            runId: Long?,
            observations: List<AvailabilityCellRepo.CellObservation>,
            reservableRidByDbId: Map<Long, String>,
        ) {
            recordCalls++
            for (observation in observations) {
                cells[observation.reservableId to observation.targetDate] = observation
            }
        }
    }
}
