package ca.floo.roadtrip.service.scheduler.jobs

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.repo.AvailabilityCellRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvailabilityPollExecutorTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_snapshot")
        ctx.execute("DELETE FROM availability_cell")
        ctx.execute("DELETE FROM availability_fetch_call")
        ctx.execute("DELETE FROM availability_run")
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch_poller")
        ctx.execute("DELETE FROM availability_poller")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    /** Seeds a campground POI whose provider_ref resolves to ProviderRef.RecGov(campgroundId). */
    private fun seedPoi(campgroundId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
                "poi-$campgroundId",
                """{"recgov_id": "$campgroundId"}""",
            )!!
            .get("id", Long::class.java)

    /** Seeds one child reservable (site) linked to [poiId]. Returns its db id. */
    private fun seedReservable(
        poiId: Long,
        siteId: String,
    ): Long {
        val reservableId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO reservables (type, vendor, vendor_id, name, source)
                    VALUES ('site', 'recgov', ?, ?, 'test')
                    RETURNING id
                    """.trimIndent(),
                    siteId,
                    "Site $siteId",
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO reservable_pois (reservable_id, poi_id) VALUES (?, ?)",
            reservableId,
            poiId,
        )
        return reservableId
    }

    /** Seeds an ACTIVE poi-scoped watch. Returns its id. */
    private fun seedWatch(
        poiId: Long,
        startDate: String,
        endDate: String,
        cadenceSec: Int = 60,
    ): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?::date, ?::date, ?, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    startDate,
                    endDate,
                    cadenceSec,
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }

    private fun membershipFor(provider: ReservationProvider): AvailabilityPollerMembership {
        val reservablesRepo = ReservableRepo(ctx)
        val registry = ReservationProviderRegistry(mapOf("test" to provider))
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                reservablesRepo = reservablesRepo,
                reservationProviders = registry,
                dateResolver = AvailabilityDateResolver(),
            )
        return AvailabilityPollerMembership(WatchScopeResolver(reservablesRepo), targets)
    }

    /** Links [watchId] onto its (provider, parentRef) poller via the production
     *  membership path, then returns the single resulting poller. */
    private fun linkWatch(
        provider: ReservationProvider,
        watchId: Long,
    ): AvailabilityPollerRepo.Poller {
        val watch = AvailabilityWatchRepo(ctx).findById(watchId)!!
        val pollers = AvailabilityPollerRepo(ctx)
        membershipFor(provider).sync(watch, pollers, tighterCadencePull = now())
        val pollerId = pollers.pollerIdsForWatch(watchId).single()
        return pollers.findById(pollerId)!!
    }

    private fun executorFor(provider: ReservationProvider): AvailabilityPollExecutor {
        val reservablesRepo = ReservableRepo(ctx)
        val registry = ReservationProviderRegistry(mapOf("test" to provider))
        val dateResolver = AvailabilityDateResolver()
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                reservablesRepo = reservablesRepo,
                reservationProviders = registry,
                dateResolver = dateResolver,
            )
        return AvailabilityPollExecutor(
            pollers = AvailabilityPollerRepo(ctx),
            reservablesRepo = reservablesRepo,
            batcher = CatalogAvailabilityBatcher(),
            snapshots = AvailabilitySnapshotRepo(ctx),
            cells = AvailabilityCellRepo(ctx),
            runs = AvailabilityRunRepo(ctx),
            dateResolver = dateResolver,
            targets = targets,
            fetchCalls = AvailabilityFetchCallRepo(ctx),
        )
    }

    /** Fake provider that records each catalogAvailability call's window and
     *  returns one observation per requested reservable/day. */
    private class CountingRecgovProvider(
        var status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    ) : ReservationProvider {
        var calls: Int = 0
        var lastStart: LocalDate? = null
        var lastEnd: LocalDate? = null
        var lastReservableCount: Int = 0
        var mdcRunIdDuringCall: String? = null

        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 3650,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
            calls++
            lastStart = req.startDate
            lastEnd = req.endDate
            lastReservableCount = req.reservables.size
            mdcRunIdDuringCall = MDC.get("run_id")
            val observedAt = Instant.now()
            val observations =
                req.reservables.map { ref ->
                    ReservableDayObservation(
                        reservableId = ref.rid,
                        date = req.startDate,
                        observedAt = observedAt,
                        status = status,
                    )
                }
            return AvailabilityObservationBatch(
                provider = "recgov",
                startDate = req.startDate,
                endDate = req.endDate,
                observations = observations,
                cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
            )
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")
    }

    private class RateLimitedProvider : ReservationProvider {
        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 3650,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch =
            throw ReservationProviderError.RateLimited(RuntimeException("429"))

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")
    }

    // A window well in the future so both watches are fully live under the
    // target-local clamp; provider bookingHorizonDays is set high enough to cover it.
    private val farStart = LocalDate.now(ZoneOffset.UTC).plusYears(1)

    @Test
    fun `two live watches on one poller makes one fetch over the union window`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            listOf("100", "101", "102").forEach { seedReservable(poiId, it) }
            // watchA: [+1y+5, +1y+7]; watchB: [+1y, +1y+2]. Union = [+1y, +1y+7].
            val watchA = seedWatch(poiId, farStart.plusDays(5).toString(), farStart.plusDays(7).toString())
            val watchB = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchA)
            // watchB coalesces onto the same poller.
            AvailabilityWatchRepo(ctx).findById(watchB)!!.let { w ->
                membershipFor(provider).sync(w, AvailabilityPollerRepo(ctx), tighterCadencePull = now())
            }

            executorFor(provider).handle(poller)

            // ONE upstream call covering the union window and all 3 sites once each.
            assertEquals(1, provider.calls)
            assertEquals(farStart, provider.lastStart)
            assertEquals(farStart.plusDays(7), provider.lastEnd)

            val runs = AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10)
            assertEquals(1, runs.size)
            assertEquals("completed", runs[0].status)

            val fetchCalls = AvailabilityFetchCallRepo(ctx).listForRun(runs[0].id)
            assertEquals(1, fetchCalls.size)
            assertEquals("ok", fetchCalls[0].outcome)
            assertEquals(3, fetchCalls[0].reservableCount)
            assertEquals("232447", fetchCalls[0].parentRef)
        }

    @Test
    fun `cadence is the min over live watches`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchSlow = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 300)
            val watchFast = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 30)
            val poller = linkWatch(provider, watchSlow)
            AvailabilityWatchRepo(ctx).findById(watchFast)!!.let { w ->
                membershipFor(provider).sync(w, AvailabilityPollerRepo(ctx), tighterCadencePull = now())
            }

            val before = OffsetDateTime.now()
            val result = executorFor(provider).handle(poller)

            // min(300, 30) = 30s on success.
            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertEquals(30L, delaySec)
        }

    @Test
    fun `empty window retires the poller and does not fetch`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            // A watch whose window is entirely in the past.
            val watchId = seedWatch(poiId, "2020-01-01", "2020-01-05")
            // Link it directly (membership's liveness is based on ACTIVE + end>=today,
            // and this end_date is in the past, so link manually to exercise the reaper).
            val pollers = AvailabilityPollerRepo(ctx)
            val pollerId =
                pollers.upsertActive(provider = "recgov", parentRef = "232447", poiId = poiId, pullNextRunAt = now())
            pollers.linkWatch(watchId, pollerId)
            val poller = pollers.findById(pollerId)!!

            executorFor(provider).handle(poller)

            // No upstream call, no run row.
            assertEquals(0, provider.calls)
            assertEquals(0, AvailabilityRunRepo(ctx).listForPoller(pollerId, limit = 10).size)
            // Poller retired: deactivated, links dropped, watch marked done.
            assertEquals(false, pollers.findById(pollerId)!!.active)
            assertTrue(pollers.watchIdsForPoller(pollerId).isEmpty())
            val watchStatus =
                ctx
                    .fetchOne("SELECT status FROM availability_watch WHERE id = ?", watchId)!!
                    .get("status", String::class.java)
            assertEquals("done", watchStatus)
        }

    @Test
    fun `failure backs off using derived cadence and consecutive failures`() =
        runBlocking {
            val provider = RateLimitedProvider()
            val poiId = seedPoi("232447")
            seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString(), cadenceSec = 120)
            val poller = linkWatch(provider, watchId)
            val runsRepo = AvailabilityRunRepo(ctx)

            // Seed one prior failed run so this run's failure is the 2nd consecutive.
            val priorRunId = runsRepo.start(poller.id, now().minusMinutes(5))
            runsRepo.fail(priorRunId, error = "rate_limited", completedAt = now().minusMinutes(4), durationMs = 0)

            val before = OffsetDateTime.now()
            val result = executorFor(provider).handle(poller)

            val runs = runsRepo.listForPoller(poller.id, limit = 10)
            assertEquals("failed", runs[0].status)
            assertEquals("rate_limited", runs[0].error)

            // 2 consecutive failures -> 120 * 2^2 = 480s, above the flat 120s cadence,
            // and comfortably under BACKOFF_CEILING_SEC (3600s).
            val delaySec = Duration.between(before, result.nextRunAt).seconds
            assertTrue(delaySec in 400..3_600L)
        }

    @Test
    fun `first-sight observations each write a transition snapshot row`() =
        runBlocking {
            val provider = CountingRecgovProvider()
            val poiId = seedPoi("232447")
            listOf("100", "101").forEach { seedReservable(poiId, it) }
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)

            executorFor(provider).handle(poller)

            val runs = AvailabilityRunRepo(ctx).listForPoller(poller.id, limit = 10)
            assertEquals("completed", runs[0].status)
            assertTrue(runs[0].snapshotCount > 0)

            val snapshots = AvailabilitySnapshotRepo(ctx).listForRun(runs[0].id, limit = 100)
            // Provider returns one observation per reservable (2 sites) for the window
            // start; both are first-sight, so both are transitions.
            assertEquals(2, snapshots.size)
            assertTrue(snapshots.all { it.runId == runs[0].id })

            // MDC run_id propagated across the coroutine dispatch and cleared after.
            assertEquals(runs[0].id.toString(), provider.mdcRunIdDuringCall)
            assertNull(MDC.get("run_id"), "MDC should be cleared on this thread after handle() returns")
        }

    @Test
    fun `unchanged status across two runs upserts the cell but writes no second snapshot row`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)
            val snapshotRepo = AvailabilitySnapshotRepo(ctx)
            val cellRepo = AvailabilityCellRepo(ctx)

            executorFor(provider).handle(poller)
            val snapshotsAfterFirst = snapshotRepo.listForReservable(reservableId)
            val cellAfterFirst = cellRepo.loadCells(listOf(reservableId), listOf(farStart)).single()

            Thread.sleep(5)
            executorFor(provider).handle(poller)
            val snapshotsAfterSecond = snapshotRepo.listForReservable(reservableId)

            // No new snapshot row: the status did not change.
            assertEquals(snapshotsAfterFirst.size, snapshotsAfterSecond.size)
            // But the cell's liveness advanced.
            val cellAfterSecond = cellRepo.loadCells(listOf(reservableId), listOf(farStart)).single()
            assertTrue(cellAfterSecond.lastObservedAt.isAfter(cellAfterFirst.lastObservedAt))
            assertEquals(cellAfterFirst.lastChangedAt, cellAfterSecond.lastChangedAt)
        }

    @Test
    fun `a status change writes exactly one new snapshot row (the transition)`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            val reservableId = seedReservable(poiId, "100")
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)
            val snapshotRepo = AvailabilitySnapshotRepo(ctx)

            executorFor(provider).handle(poller)
            val before = snapshotRepo.listForReservable(reservableId).size

            provider.status = AvailabilityStatus.RESERVED
            executorFor(provider).handle(poller)
            val after = snapshotRepo.listForReservable(reservableId).size

            assertEquals(before + 1, after)
        }

    @Test
    fun `run snapshot_count reflects transitions, not raw observation count`() =
        runBlocking {
            val provider = CountingRecgovProvider(status = AvailabilityStatus.AVAILABLE)
            val poiId = seedPoi("232447")
            listOf("100", "101", "102").forEach { seedReservable(poiId, it) }
            val watchId = seedWatch(poiId, farStart.toString(), farStart.plusDays(2).toString())
            val poller = linkWatch(provider, watchId)
            val runsRepo = AvailabilityRunRepo(ctx)

            // Run 1: 3 reservables, all first-sight -> 3 transitions.
            executorFor(provider).handle(poller)
            val run1 = runsRepo.listForPoller(poller.id, limit = 10).first()
            assertEquals(3, run1.snapshotCount)

            // Run 2: identical statuses -> 0 transitions.
            executorFor(provider).handle(poller)
            val run2 = runsRepo.listForPoller(poller.id, limit = 10).first()
            assertEquals(0, run2.snapshotCount)
        }
}
