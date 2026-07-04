package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvailabilitySnapshotStatsTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_snapshot")
        ctx.execute("DELETE FROM availability_cell")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    private fun insertCell(
        reservableId: Long,
        targetDate: LocalDate,
        status: String,
        lastObservedAt: OffsetDateTime,
        lastChangedAt: OffsetDateTime,
    ) {
        ctx.execute(
            """
            INSERT INTO availability_cell (
                reservable_id, target_date, status, last_observed_at, last_changed_at
            ) VALUES (?::bigint, ?::date, ?::availability_status, ?::timestamptz, ?::timestamptz)
            """.trimIndent(),
            reservableId,
            targetDate.toString(),
            status,
            lastObservedAt.toString(),
            lastChangedAt.toString(),
        )
    }

    private fun seedReservable(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name
                ) VALUES (
                    'site', 'recgov', '330257', 'federal-campsites', 'A12'
                ) RETURNING id
                """.trimIndent(),
            )!!
            .get("id", Long::class.java)

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: LocalDate,
        observedAt: OffsetDateTime,
        available: Boolean,
    ) {
        ctx.execute(
            """
            INSERT INTO availability_snapshot (
                reservable_id, observed_at, target_date, status, available, day_payload
            ) VALUES (?::bigint, ?::timestamptz, ?::date, ?::availability_status, ?::boolean, '{}'::jsonb)
            """.trimIndent(),
            reservableId,
            observedAt.toString(),
            targetDate.toString(),
            if (available) "available" else "reserved",
            available,
        )
    }

    private val date = LocalDate.parse("2026-07-04")

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `empty input yields zeroed stats per target_date`() {
        val reservableId = seedReservable()
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now())
        assertEquals(1, stats.size)
        assertEquals(0, stats[0].totalSnapshots)
        assertNull(stats[0].lastOpenAt)
        assertNull(stats[0].currentOrLastOpenWindowSec)
        assertNull(stats[0].medianOpenWindowSec)
        assertEquals(0, stats[0].flipsLast24h)
        assertEquals(false, stats[0].isCurrentlyOpen)
    }

    @Test
    fun `all booked window yields zeroed run stats with totalSnapshots populated`() {
        val reservableId = seedReservable()
        val now = now()
        repeat(5) {
            insertSnapshot(reservableId, date, now.minusMinutes((5 - it).toLong()), available = false)
        }
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        assertEquals(5, stats.totalSnapshots)
        assertNull(stats.lastOpenAt)
        assertEquals(false, stats.isCurrentlyOpen)
        assertNull(stats.currentOrLastOpenWindowSec)
        assertNull(stats.medianOpenWindowSec)
        assertEquals(0, stats.flipsLast24h)
    }

    @Test
    fun `one closed run computes lastOpenAt and window duration`() {
        val reservableId = seedReservable()
        val now = now()
        // 3 booked, 2 available, 2 booked → one run of length 1m (we count seconds between first true and last true within run).
        insertSnapshot(reservableId, date, now.minusMinutes(7), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(6), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(5), available = false)
        val openAt1 = now.minusMinutes(4)
        val openAt2 = now.minusMinutes(3)
        insertSnapshot(reservableId, date, openAt1, available = true)
        insertSnapshot(reservableId, date, openAt2, available = true)
        insertSnapshot(reservableId, date, now.minusMinutes(2), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(1), available = false)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        assertEquals(7, stats.totalSnapshots)
        assertEquals(false, stats.isCurrentlyOpen)
        assertNotNull(stats.lastOpenAt)
        assertEquals(openAt2.toEpochSecond(), stats.lastOpenAt!!.toEpochSecond())
        assertNotNull(stats.currentOrLastOpenWindowSec)
        assertTrue(stats.currentOrLastOpenWindowSec!! in 55..65) // ~60s
        assertEquals(stats.currentOrLastOpenWindowSec, stats.medianOpenWindowSec) // single run
        assertEquals(1, stats.flipsLast24h)
    }

    @Test
    fun `currently open run reports isCurrentlyOpen=true`() {
        val reservableId = seedReservable()
        val now = now()
        insertSnapshot(reservableId, date, now.minusMinutes(3), available = false)
        insertSnapshot(reservableId, date, now.minusMinutes(2), available = true)
        insertSnapshot(reservableId, date, now.minusMinutes(1), available = true)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        assertEquals(true, stats.isCurrentlyOpen)
        assertNotNull(stats.lastOpenAt)
        assertNotNull(stats.currentOrLastOpenWindowSec)
        assertEquals(1, stats.flipsLast24h)
    }

    @Test
    fun `multiple runs compute median across runs`() {
        val reservableId = seedReservable()
        val now = now()
        // Three runs of 30s, 120s, 60s.
        insertSnapshot(reservableId, date, now.minusSeconds(700), available = false)
        insertSnapshot(reservableId, date, now.minusSeconds(630), available = true) // r1 start
        insertSnapshot(reservableId, date, now.minusSeconds(600), available = true) // r1 end (30s)
        insertSnapshot(reservableId, date, now.minusSeconds(570), available = false)
        insertSnapshot(reservableId, date, now.minusSeconds(450), available = true) // r2 start
        insertSnapshot(reservableId, date, now.minusSeconds(330), available = true) // r2 end (120s)
        insertSnapshot(reservableId, date, now.minusSeconds(300), available = false)
        insertSnapshot(reservableId, date, now.minusSeconds(180), available = true) // r3 start
        insertSnapshot(reservableId, date, now.minusSeconds(120), available = true) // r3 end (60s)
        insertSnapshot(reservableId, date, now.minusSeconds(60), available = false)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()
        // Three runs of roughly 30, 120, 60 seconds. Median = 60.
        assertNotNull(stats.medianOpenWindowSec)
        assertTrue(stats.medianOpenWindowSec!! in 55..65)
        // Most recent run is r3 (60s).
        assertNotNull(stats.currentOrLastOpenWindowSec)
        assertTrue(stats.currentOrLastOpenWindowSec!! in 55..65)
        // Three false→true transitions in the last 24h.
        assertEquals(3, stats.flipsLast24h)
    }

    @Test
    fun `multiple dates returned in input order`() {
        val reservableId = seedReservable()
        val now = now()
        val d1 = LocalDate.parse("2026-07-04")
        val d2 = LocalDate.parse("2026-07-05")
        insertSnapshot(reservableId, d1, now.minusMinutes(2), available = true)
        insertSnapshot(reservableId, d1, now.minusMinutes(1), available = true)
        insertSnapshot(reservableId, d2, now.minusMinutes(2), available = false)
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(d1, d2), now)
        assertEquals(2, stats.size)
        assertEquals(d1, stats[0].targetDate)
        assertEquals(d2, stats[1].targetDate)
        assertEquals(true, stats[0].isCurrentlyOpen)
        assertEquals(false, stats[1].isCurrentlyOpen)
    }

    @Test
    fun `cube reports currently-open with a real window when the last edge predates the window`() {
        // Edge-only history: the cell became available 10 days ago and stayed
        // available, so its only snapshot edge is before the 7-day window and
        // there are NO in-window rows. Pre-cube this returned zeroed / not-open;
        // now the cube is authoritative.
        val reservableId = seedReservable()
        val now = now()
        insertSnapshot(reservableId, date, now.minusDays(10), available = true) // pre-window edge (seed)
        insertCell(
            reservableId,
            date,
            status = "available",
            lastObservedAt = now.minusMinutes(1),
            lastChangedAt = now.minusDays(10),
        )
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()

        assertEquals(0, stats.totalSnapshots, "no rows inside the 7d window")
        assertEquals(true, stats.isCurrentlyOpen, "cube says available")
        assertNotNull(stats.currentOrLastOpenWindowSec)
        // ~10 days open, measured from the cube's last_changed_at, not collapsed to 0.
        assertTrue(stats.currentOrLastOpenWindowSec!! > 9 * 24 * 3600)
        assertNotNull(stats.lastOpenAt)
    }

    @Test
    fun `cube status overrides a stale in-window snapshot for isCurrentlyOpen`() {
        // Window has an old 'reserved' edge, but the cube now says available.
        val reservableId = seedReservable()
        val now = now()
        insertSnapshot(reservableId, date, now.minusHours(30), available = false)
        insertCell(
            reservableId,
            date,
            status = "available",
            lastObservedAt = now.minusMinutes(1),
            lastChangedAt = now.minusHours(2),
        )
        val repo = AvailabilitySnapshotRepo(ctx)
        val stats = repo.summarize(reservableId, listOf(date), now).single()

        assertEquals(true, stats.isCurrentlyOpen)
        assertNotNull(stats.currentOrLastOpenWindowSec)
    }
}
