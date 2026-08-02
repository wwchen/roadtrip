package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityPoller.Companion.AVAILABILITY_POLLER
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AvailabilityPollerRepoTest : SharedDbTest() {
    private var userSeq = 0

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    private fun seedOwner(): Long = UserRepo(ctx).create(
        email = "owner-${userSeq++}@example.com",
        displayName = null,
        isEmailVerified = true,
    ).id.value

    private fun insertPoi(): Long = ctx.seedCatalogPoi(sourceId = "p1", name = "Upper Pines", lon = -119.56, lat = 37.74).poiId

    /** Inserts an `availability_watch` row that is active with a future end_date. */
    private fun insertActiveWatch(
        poiId: Long,
        startDate: String = "2026-07-04",
        endDate: String = "2026-12-31",
    ): Long {
        val ownerId = seedOwner()
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        owner_user_id, start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?, ?::date, ?::date, 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    ownerId,
                    startDate,
                    endDate,
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }

    /** Inserts a paused watch (excluded from `liveWatchesForPoller`). */
    private fun insertPausedWatch(
        poiId: Long,
        startDate: String = "2026-07-04",
        endDate: String = "2026-12-31",
    ): Long {
        val ownerId = seedOwner()
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        owner_user_id, start_date, end_date, cadence_sec, trigger_kinds, status
                    ) VALUES (
                        ?, ?::date, ?::date, 60, ARRAY['atc'], 'paused'
                    ) RETURNING id
                    """.trimIndent(),
                    ownerId,
                    startDate,
                    endDate,
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }

    /** Inserts an active watch whose end_date is already in the past (elapsed). */
    private fun insertElapsedWatch(poiId: Long): Long = insertActiveWatch(poiId, startDate = "2020-01-01", endDate = "2020-01-02")

    private fun watchStatus(watchId: Long): String =
        ctx
            .fetchOne("SELECT status FROM availability_watch WHERE id = ?", watchId)!!
            .get("status", String::class.java)

    /** Test-only helper: parks a poller's next_run_at far in the future so claimDue skips it. */
    private fun AvailabilityPollerRepo.parkFar(pollerId: Long) {
        ctx
            .update(AVAILABILITY_POLLER)
            .set(AVAILABILITY_POLLER.NEXT_RUN_AT, now().plusDays(30))
            .where(AVAILABILITY_POLLER.ID.eq(pollerId))
            .execute()
    }

    @Test
    fun `upsertActive inserts once per provider+parentRef and revives a dormant poller`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val id1 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = null)
        val id2 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = null)
        assertEquals(id1, id2) // UNIQUE(provider,parent_ref)
        repo.deactivatePollersWithNoLinks() // no links -> dormant
        assertFalse(repo.findById(id1)!!.active)
        val id3 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = OffsetDateTime.now())
        assertEquals(id1, id3)
        assertTrue(repo.findById(id1)!!.active) // revived
    }

    @Test
    fun `claimDue returns only active due pollers and leases them`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val due = repo.upsertActive("recgov", "A", poi, null)
        val notDue = repo.upsertActive("recgov", "B", poi, null)
        repo.parkFar(notDue) // test helper: direct UPDATE parking next_run_at far future
        val claimed = repo.claimDue(OffsetDateTime.now(), limit = 10, leaseDuration = Duration.ofMinutes(2))
        assertEquals(listOf(due), claimed.map { it.id })
        assertNotNull(claimed.single().claimToken)
    }

    @Test
    fun `reapElapsedWatches marks elapsed watches done, drops links, deactivates orphaned pollers`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val watch = insertElapsedWatch(poiId = poi) // end_date < today
        val poller = repo.upsertActive("recgov", "A", poi, null)
        repo.linkWatch(watch, poller)

        val outcome = repo.reapElapsedWatches()

        // The outcome carries the exact ids the reaper audits.
        assertEquals(listOf(watch), outcome.reapedWatchIds)
        assertEquals(listOf(poller), outcome.deactivatedPollerIds)
        assertFalse(repo.findById(poller)!!.active)
        assertTrue(repo.pollerIdsForWatch(watch).isEmpty())
        assertEquals("done", watchStatus(watch)) // helper reads availability_watch.status
    }

    @Test
    fun `reapElapsedWatches reaps only elapsed watches and keeps a live watch's poller active`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)
        val elapsed = insertElapsedWatch(poiId = poi) // end_date < today
        val live = insertActiveWatch(poiId = poi) // end_date far future
        repo.linkWatch(elapsed, poller)
        repo.linkWatch(live, poller)

        val outcome = repo.reapElapsedWatches()

        assertEquals(1, outcome.watchesReaped)
        assertEquals(0, outcome.pollersDeactivated)
        assertEquals("done", watchStatus(elapsed))
        assertTrue(repo.pollerIdsForWatch(elapsed).isEmpty()) // elapsed link dropped
        assertEquals("active", watchStatus(live)) // NOT marked done: end_date >= today
        assertEquals(listOf(poller), repo.pollerIdsForWatch(live)) // live link kept
        assertTrue(repo.findById(poller)!!.active) // poller stays active (link remains)
    }

    @Test
    fun `reapElapsedWatches leaves a watch whose end_date is today alone (predicate enforced at mutation)`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)
        // end_date == today: NOT < CURRENT_DATE, so still live. This is exactly
        // where a watch a user extended to today lands. Since the liveness check
        // lives in the UPDATE's own WHERE (not a prior SELECT), the mutation
        // itself must exclude it — proving proof and mutation are atomic.
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val watch = insertActiveWatch(poiId = poi, startDate = "2020-01-01", endDate = today)
        repo.linkWatch(watch, poller)

        val outcome = repo.reapElapsedWatches()

        assertEquals(emptyList(), outcome.reapedWatchIds)
        assertEquals("active", watchStatus(watch))
        assertEquals(listOf(poller), repo.pollerIdsForWatch(watch))
        assertTrue(repo.findById(poller)!!.active)
    }

    @Test
    fun `reapElapsedWatches deactivates a poller whose every watch elapsed`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)
        val e1 = insertElapsedWatch(poiId = poi)
        val e2 = insertElapsedWatch(poiId = poi)
        repo.linkWatch(e1, poller)
        repo.linkWatch(e2, poller)

        val outcome = repo.reapElapsedWatches()

        assertEquals(2, outcome.watchesReaped)
        assertEquals(1, outcome.pollersDeactivated)
        assertEquals("done", watchStatus(e1))
        assertEquals("done", watchStatus(e2))
        assertTrue(repo.pollerIdsForWatch(e1).isEmpty())
        assertTrue(repo.pollerIdsForWatch(e2).isEmpty())
        assertFalse(repo.findById(poller)!!.active) // zero links -> dormant
    }

    @Test
    fun `reapElapsedWatches is a no-op when nothing has elapsed`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)
        val live = insertActiveWatch(poiId = poi)
        repo.linkWatch(live, poller)

        val outcome = repo.reapElapsedWatches()

        assertEquals(0, outcome.watchesReaped)
        assertEquals(0, outcome.pollersDeactivated)
        assertEquals("active", watchStatus(live))
        assertTrue(repo.findById(poller)!!.active)
    }

    @Test
    fun `liveWatchesForPoller returns active watches with a future end_date only`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)

        val live = insertActiveWatch(poiId = poi)
        val paused = insertPausedWatch(poiId = poi)
        val elapsed = insertElapsedWatch(poiId = poi)

        repo.linkWatch(live, poller)
        repo.linkWatch(paused, poller)
        repo.linkWatch(elapsed, poller)

        val result = repo.liveWatchesForPoller(poller)
        assertEquals(listOf(live), result.map { it.id })
    }

    @Test
    fun `watchIdsForPoller returns all linked watch ids regardless of status`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)

        val live = insertActiveWatch(poiId = poi)
        val paused = insertPausedWatch(poiId = poi)

        repo.linkWatch(live, poller)
        repo.linkWatch(paused, poller)

        assertEquals(setOf(live, paused), repo.watchIdsForPoller(poller).toSet())
    }

    @Test
    fun `release advances nextRunAt only with matching token`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, now().minusMinutes(1))
        val claimed = repo.claimDue(now(), limit = 1, leaseDuration = Duration.ofSeconds(30))[0]
        val nextRun = now().plusMinutes(1)
        assertTrue(repo.release(claimed.id, claimed.claimToken!!, nextRun, now()))
        val after = repo.findById(claimed.id)!!
        assertEquals(null, after.claimToken)
        assertEquals(nextRun.toEpochSecond(), after.nextRunAt.toEpochSecond())
        // Wrong token: no-op.
        assertFalse(repo.release(claimed.id, "wrong-token", nextRun.plusMinutes(1), now()))
    }

    @Test
    fun `reclaimExpired clears expired leases`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val baseTime = now()
        repo.upsertActive("recgov", "A", poi, baseTime.minusMinutes(2))
        repo.claimDue(baseTime.minusMinutes(1), limit = 1, leaseDuration = Duration.ofSeconds(10))
        val reclaimed = repo.reclaimExpired(baseTime)
        assertEquals(1, reclaimed)
    }

    @Test
    fun `forcePull sets next_run_at to now and stamps last_force_pull_at when outside cooldown`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val pollerId = repo.upsertActive("recgov", "A", poi, pullNextRunAt = now().plusHours(1))
        val forceAt = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val result = repo.forcePull(pollerId, forceAt, cooldown = Duration.ofSeconds(30))
        assertTrue(result is AvailabilityPollerRepo.ForcePullResult.Accepted)
        // isEqual compares the instant, not the offset — the Postgres round-trip
        // comes back at the JVM's local offset while forceAt is UTC.
        assertTrue(forceAt.isEqual(repo.findById(pollerId)!!.nextRunAt))
        assertTrue(forceAt.isEqual(repo.findById(pollerId)!!.lastForcePullAt!!))
    }

    @Test
    fun `forcePull rejects a second call inside the cooldown window`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val pollerId = repo.upsertActive("recgov", "A", poi, pullNextRunAt = null)
        val forceAt = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        repo.forcePull(pollerId, forceAt, cooldown = Duration.ofSeconds(30))
        val second = repo.forcePull(pollerId, forceAt.plusSeconds(5), cooldown = Duration.ofSeconds(30))
        assertTrue(second is AvailabilityPollerRepo.ForcePullResult.Cooldown)
        val remaining = second.retryAfterSec
        assertEquals(25L, remaining) // 30s cooldown - 5s elapsed
    }

    @Test
    fun `forcePull succeeds again once the cooldown has elapsed`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val pollerId = repo.upsertActive("recgov", "A", poi, pullNextRunAt = null)
        val forceAt = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        repo.forcePull(pollerId, forceAt, cooldown = Duration.ofSeconds(30))
        val later = repo.forcePull(pollerId, forceAt.plusSeconds(31), cooldown = Duration.ofSeconds(30))
        assertTrue(later is AvailabilityPollerRepo.ForcePullResult.Accepted)
    }

    @Test
    fun `forcePull on an unknown poller id returns NotFound`() {
        val repo = AvailabilityPollerRepo(ctx)
        val result = repo.forcePull(pollerId = 999_999L, now = now(), cooldown = Duration.ofSeconds(30))
        assertEquals(AvailabilityPollerRepo.ForcePullResult.NotFound, result)
    }

    @Test
    fun `concurrent force-pull calls inside the cooldown only one wins`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val pollerId = repo.upsertActive("recgov", "A", poi, pullNextRunAt = null)
        val forceAt = now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val threads = 8
        val results = java.util.Collections.synchronizedList(mutableListOf<AvailabilityPollerRepo.ForcePullResult>())
        val barrier = java.util.concurrent.CyclicBarrier(threads)
        val workers =
            (1..threads).map {
                Thread {
                    barrier.await()
                    results.add(repo.forcePull(pollerId, forceAt, cooldown = Duration.ofSeconds(30)))
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val accepted = results.count { it is AvailabilityPollerRepo.ForcePullResult.Accepted }
        assertEquals(1, accepted) // WHERE-embedded cooldown check: exactly one winner
    }

    @Test
    fun `pollerIdsForWatch reflects linkWatch and replaceLinksForWatch`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val watch = insertActiveWatch(poiId = poi)
        val pollerA = repo.upsertActive("recgov", "A", poi, null)
        val pollerB = repo.upsertActive("recgov", "B", poi, null)

        repo.linkWatch(watch, pollerA)
        assertEquals(listOf(pollerA), repo.pollerIdsForWatch(watch))

        repo.replaceLinksForWatch(watch, setOf(pollerB))
        assertEquals(listOf(pollerB), repo.pollerIdsForWatch(watch))
    }
}
