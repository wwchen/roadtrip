package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals

class AvailabilityRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    private fun seedReservable(vendorId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (type, vendor, vendor_id, source, name)
                VALUES ('site', 'recgov', ?, 'federal-campsites', 'site') RETURNING id
                """.trimIndent(),
                vendorId,
            )!!
            .get("id", Long::class.java)

    private val date = LocalDate.parse("2026-07-04")

    @Test
    fun `unchanged status bumps last_observed_at in place, no new row`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:05:00Z")
        repo.recordObservations(runId = null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t1)))
        val transitions =
            repo.recordObservations(
                runId = null,
                listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t2)),
            )
        assertEquals(0, transitions.size)
        assertEquals(1, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(rid), listOf(date)).single()
        assertEquals(t2, current.observedAt.toInstant())
    }

    @Test
    fun `status change inserts a new row linked by previous_id`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:05:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t1)))
        val transitions = repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.AVAILABLE, t2)))
        assertEquals(1, transitions.size)
        assertEquals(2, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(rid), listOf(date)).single()
        assertEquals(AvailabilityStatus.AVAILABLE, current.status)
    }

    @Test
    fun `markElapsedAsPast adds a past status-run for elapsed cells only`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val past = LocalDate.parse("2026-06-01")
        val future = LocalDate.parse("2026-08-01")
        val t = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(
            null,
            listOf(
                AvailabilityRepo.Observation(rid, past, AvailabilityStatus.RESERVED, t),
                AvailabilityRepo.Observation(rid, future, AvailabilityStatus.RESERVED, t),
            ),
        )
        val inserted = repo.markElapsedAsPast(listOf(rid), today = LocalDate.parse("2026-07-04"))
        assertEquals(1, inserted)
        assertEquals(AvailabilityStatus.PAST, repo.readCurrent(listOf(rid), listOf(past)).single().status)
        assertEquals(AvailabilityStatus.RESERVED, repo.readCurrent(listOf(rid), listOf(future)).single().status)
    }

    @Test
    fun `markElapsedAsPast is idempotent - an already-past cell is not re-marked`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val past = LocalDate.parse("2026-06-01")
        val t = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, past, AvailabilityStatus.RESERVED, t)))
        val today = LocalDate.parse("2026-07-04")
        assertEquals(1, repo.markElapsedAsPast(listOf(rid), today = today))
        // Second run must not insert a duplicate past-run (would violate the previous_id chain).
        assertEquals(0, repo.markElapsedAsPast(listOf(rid), today = today))
        assertEquals(2, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
    }

    @Test
    fun `history walks the previous_id chain, observedFrom derives from previous`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T11:00:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t1)))
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.AVAILABLE, t2)))
        val runs = repo.listForReservable(rid)
        assertEquals(2, runs.size)
        val current = runs.first { it.status == AvailabilityStatus.AVAILABLE }
        assertEquals(t1, current.observedFrom!!.toInstant()) // start = prior run's last_observed_at
    }

    @Test
    fun `summarize reports an open window from an available run`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val t0 = Instant.parse("2026-06-18T10:00:00Z") // reserved
        val t1 = Instant.parse("2026-06-18T10:30:00Z") // flips to available
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.RESERVED, t0)))
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, date, AvailabilityStatus.AVAILABLE, t1)))
        val stats = repo.summarize(rid, listOf(date), now = OffsetDateTime.parse("2026-06-18T12:00:00Z"))
        val s = stats.single()
        assertEquals(true, s.isCurrentlyOpen)
        assertEquals(1800, s.currentOrLastOpenWindowSec) // available run spans t0..t1 = 30 min
    }

    @Test
    fun `summarize keeps a cell's current state even when its last observation predates the window`() {
        val rid = seedReservable("100")
        val repo = AvailabilityRepo(ctx)
        val future = LocalDate.parse("2026-09-01")
        // Observed once, long before any reasonable summary window.
        val longAgo = Instant.parse("2026-01-01T00:00:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(rid, future, AvailabilityStatus.AVAILABLE, longAgo)))
        val now = OffsetDateTime.parse("2026-07-05T00:00:00Z")
        val s = repo.summarize(rid, listOf(future), now = now, windowHours = 24 * 7).single()
        // The current row predates the 7-day window, but the date must still report
        // its true state, not zero-runs/closed.
        assertEquals(true, s.isCurrentlyOpen)
        assertEquals(1, s.totalRuns)
        assertEquals(0, s.opensLast24h)
    }
}
