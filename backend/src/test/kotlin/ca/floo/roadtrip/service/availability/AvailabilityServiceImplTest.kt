package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotStore
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
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
 * Unit tests for [AvailabilityServiceImpl.getByRids] and its delegation to
 * [CatalogAvailabilityBatcher] + the snapshot cache wrapper. Uses in-memory
 * fakes for the target resolver, reservation provider, and snapshot store —
 * consistent with [CatalogAvailabilityBatcherTest]; no DB required.
 */
class AvailabilityServiceImplTest {
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
            val store = FakeSnapshotStore()
            val service = service(listOf(target), store)

            val first = service.getByRids(listOf(target.rid()), null, null, force = false)
            assertEquals(1, provider.catalogCalls, "cache miss should fetch upstream exactly once")
            assertEquals(1, store.appendCalls, "cache miss should persist a snapshot")
            assertFalse(first.single().cache.hit, "first read is a miss")

            val second = service.getByRids(listOf(target.rid()), null, null, force = false)
            assertEquals(1, provider.catalogCalls, "repeat read must be served from cache, not re-fetched")
            assertEquals(1, store.appendCalls, "cache hit should not write again")
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
            val service = service(listOf(targetA, targetB), FakeSnapshotStore())

            // Request order is B then A; results must come back in the requested order.
            val results = service.getByRids(listOf(targetB.rid(), targetA.rid()), null, null, force = true)

            assertEquals(2, provider.catalogCalls, "distinct groups fetch independently")
            assertEquals(2, results.size)
            assertEquals("site:recgov:200", results[0].reservableId)
            assertEquals("site:recgov:100", results[1].reservableId)
            // A's window is fully AVAILABLE; B's is fully RESERVED.
            assertTrue(results[1].availability.all { it.status == AvailabilityStatus.AVAILABLE })
            assertTrue(results[0].availability.all { it.status == AvailabilityStatus.RESERVED })
        }

    @Test
    fun `bad date window from the date resolver propagates out of getByRids`() =
        runBlocking {
            val provider = fakeProvider()
            val target = resolvedTarget("site:recgov:100", dbId = 1L, provider = provider, parentRef = ProviderRef.RecGov("100"))
            val service = service(listOf(target), FakeSnapshotStore())

            // startDate before the target's earliest bookable date → StartBeforeEarliest.
            assertFailsWith<AvailabilityServiceError.BadDateWindow.StartBeforeEarliest> {
                service.getByRids(listOf(target.rid()), earliest.minusDays(1), null, force = false)
            }
            assertEquals(0, provider.catalogCalls, "an invalid window must short-circuit before any upstream fetch")
        }

    @Test
    fun `requireByRid NotFound propagates before batching`() =
        runBlocking {
            val service = service(targets = emptyList(), snapshots = FakeSnapshotStore())
            val error =
                assertFailsWith<AvailabilityServiceError.NotFound> {
                    service.getByRids(listOf(ReservableId.parse("site:recgov:999")!!), null, null, force = false)
                }
            assertEquals(AvailabilityServiceError.NotFound, error)
        }

    @Test
    fun `requireByRid UnknownCampground propagates before batching`() =
        runBlocking {
            val resolver =
                object : AvailabilityTargetResolver {
                    override fun requireByRid(rid: ReservableId): ResolvedAvailabilityTarget =
                        throw AvailabilityServiceError.UnknownCampground

                    override fun resolve(reservable: Reservable): ResolvedAvailabilityTarget? = null
                }
            val service = AvailabilityServiceImpl(targets = resolver, snapshots = FakeSnapshotStore(), snapshotFreshnessTtl = { longTtl })
            val error =
                assertFailsWith<AvailabilityServiceError.UnknownCampground> {
                    service.getByRids(listOf(ReservableId.parse("site:recgov:100")!!), null, null, force = false)
                }
            assertEquals(AvailabilityServiceError.UnknownCampground, error)
        }

    @Test
    fun `getByRid delegates to getByRids and returns the single result`() =
        runBlocking {
            val provider = fakeProvider()
            val target = resolvedTarget("site:recgov:100", dbId = 1L, provider = provider, parentRef = ProviderRef.RecGov("100"))
            val service = service(listOf(target), FakeSnapshotStore())

            val dto = service.getByRid(target.rid(), null, null, force = true)
            assertEquals("site:recgov:100", dto.reservableId)
        }

    // --- fixtures ---

    private fun service(
        targets: Collection<ResolvedAvailabilityTarget>,
        snapshots: AvailabilitySnapshotStore,
    ): AvailabilityServiceImpl =
        AvailabilityServiceImpl(
            targets = FakeTargetResolver(targets.associateBy { it.rid() }),
            snapshots = snapshots,
            snapshotFreshnessTtl = { longTtl },
        )

    private fun ResolvedAvailabilityTarget.rid(): ReservableId = reservable.rid

    private fun resolvedTarget(
        rid: String,
        dbId: Long,
        provider: ReservationProvider,
        parentRef: ProviderRef,
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
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("catalogAvailability is the batched path")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
            catalogCalls++
            return onCatalog(req)
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used by getByRids")
    }

    /** In-memory [AvailabilitySnapshotStore]: keeps the latest observation per (reservableId, date). */
    private class FakeSnapshotStore : AvailabilitySnapshotStore {
        var appendCalls = 0
        private val latest = linkedMapOf<Pair<Long, LocalDate>, AvailabilitySnapshotRepo.SnapshotObservation>()

        override fun appendObservations(input: AvailabilitySnapshotRepo.SnapshotObservationBatch): Int {
            appendCalls++
            for (observation in input.observations) {
                val key = observation.reservableId to observation.targetDate
                val existing = latest[key]
                if (existing == null || !observation.observedAt.isBefore(existing.observedAt)) {
                    latest[key] = observation
                }
            }
            return input.observations.size
        }

        override fun loadLatestObservations(
            reservableIds: List<Long>,
            dates: List<LocalDate>,
        ): List<AvailabilitySnapshotRepo.LatestObservation> {
            val ids = reservableIds.toSet()
            val wanted = dates.toSet()
            return latest.values
                .filter { it.reservableId in ids && it.targetDate in wanted }
                .map { observation ->
                    AvailabilitySnapshotRepo.LatestObservation(
                        reservableId = observation.reservableId,
                        targetDate = observation.targetDate,
                        observedAt = OffsetDateTime.ofInstant(observation.observedAt, ZoneOffset.UTC),
                        status = observation.status,
                        available = observation.status.isOnlineBookable,
                    )
                }
        }
    }
}
