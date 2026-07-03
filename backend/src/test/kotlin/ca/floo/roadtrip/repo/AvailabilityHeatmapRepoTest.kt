package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityHeatmapRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_snapshot")
        ctx.execute("DELETE FROM availability_cell")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    private fun seedReservable(vendorId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name
                ) VALUES (
                    'site', 'recgov', ?, 'federal-campsites', 'site'
                ) RETURNING id
                """.trimIndent(),
                vendorId,
            )!!
            .get("id", Long::class.java)

    /** Inserts a cube cell directly (no snapshot log row) so tests prove the
     *  heatmap now reads the cell, not the append log. */
    private fun insertCell(
        reservableId: Long,
        targetDate: LocalDate,
        status: String,
        observedAt: OffsetDateTime = now(),
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
            observedAt.toString(),
            observedAt.toString(),
        )
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `empty inputs return empty result`() {
        val repo = AvailabilityHeatmapRepo(ctx)
        assertTrue(repo.loadHeatmap(emptyList(), listOf(LocalDate.parse("2026-07-04"))).isEmpty())
        assertTrue(repo.loadHeatmap(listOf(1L), emptyList()).isEmpty())
    }

    @Test
    fun `reads the cell even with zero snapshot rows for the key`() {
        val rid = seedReservable("100")
        val date = LocalDate.parse("2026-07-04")
        // Only a cell row -- no availability_snapshot row. The old DISTINCT ON
        // query returned nothing here; the cube-backed query returns the cell.
        insertCell(rid, date, status = "available")
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(rid), listOf(date))
        assertEquals(1, cells.size)
        assertEquals(rid, cells[0].reservableId)
        assertEquals(date, cells[0].targetDate)
        assertEquals(true, cells[0].available)
        assertEquals(AvailabilityStatus.AVAILABLE, cells[0].status)
    }

    @Test
    fun `available is derived from status isOnlineBookable`() {
        val rid = seedReservable("100")
        val date = LocalDate.parse("2026-07-04")
        insertCell(rid, date, status = "reserved")
        val repo = AvailabilityHeatmapRepo(ctx)
        val cell = repo.loadHeatmap(listOf(rid), listOf(date)).single()
        assertEquals(false, cell.available)
        assertEquals(AvailabilityStatus.RESERVED, cell.status)
    }

    @Test
    fun `cross product returns one cell per pair, missing pairs absent`() {
        val r1 = seedReservable("100")
        val r2 = seedReservable("200")
        val d1 = LocalDate.parse("2026-07-04")
        val d2 = LocalDate.parse("2026-07-05")
        insertCell(r1, d1, status = "available")
        insertCell(r1, d2, status = "reserved")
        insertCell(r2, d1, status = "closed")
        val repo = AvailabilityHeatmapRepo(ctx)
        val cells = repo.loadHeatmap(listOf(r1, r2), listOf(d1, d2))
        assertEquals(3, cells.size)
        val byPair = cells.associateBy { it.reservableId to it.targetDate }
        assertEquals(AvailabilityStatus.AVAILABLE, byPair[r1 to d1]!!.status)
        assertEquals(AvailabilityStatus.RESERVED, byPair[r1 to d2]!!.status)
        assertEquals(AvailabilityStatus.CLOSED, byPair[r2 to d1]!!.status)
        assertEquals(null, byPair[r2 to d2])
    }

    @Test
    fun `heatmap preserves first come and unknown and past enum statuses`() {
        val r1 = seedReservable("100")
        val r2 = seedReservable("200")
        val r3 = seedReservable("300")
        val date = LocalDate.parse("2026-07-04")
        insertCell(r1, date, status = "first_come")
        insertCell(r2, date, status = "unknown")
        insertCell(r3, date, status = "past")
        val repo = AvailabilityHeatmapRepo(ctx)

        val cells = repo.loadHeatmap(listOf(r1, r2, r3), listOf(date))

        val byReservable = cells.associateBy { it.reservableId }
        assertEquals(AvailabilityStatus.FIRST_COME, byReservable[r1]!!.status)
        assertEquals(AvailabilityStatus.UNKNOWN, byReservable[r2]!!.status)
        assertEquals(AvailabilityStatus.PAST, byReservable[r3]!!.status)
    }
}
