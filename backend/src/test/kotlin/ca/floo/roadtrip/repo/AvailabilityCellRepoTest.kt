package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilityCellRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_cell")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    private fun repo() = AvailabilityCellRepo(ctx)

    private fun reservable(vendorId: String = "100"): Long =
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

    @Test
    fun `first observation upserts a new cell and is flagged changed`() {
        val rid = reservable()
        val date = LocalDate.now().plusDays(1)
        val result =
            repo().upsertObservations(
                listOf(
                    AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.AVAILABLE, Instant.now()),
                ),
            )
        assertEquals(1, result.size)
        assertTrue(result.single().changed)
        val cell = repo().loadCells(listOf(rid), listOf(date)).single()
        assertEquals(AvailabilityStatus.AVAILABLE, cell.status)
    }

    @Test
    fun `re-observing the same status bumps last_observed_at but is not flagged changed`() {
        val rid = reservable()
        val date = LocalDate.now().plusDays(1)
        val repo = repo()
        repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.AVAILABLE, Instant.now())))
        val before = repo.loadCells(listOf(rid), listOf(date)).single()
        Thread.sleep(5)
        val result = repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.AVAILABLE, Instant.now())))
        assertFalse(result.single().changed)
        val after = repo.loadCells(listOf(rid), listOf(date)).single()
        assertTrue(after.lastObservedAt.isAfter(before.lastObservedAt))
        assertEquals(before.lastChangedAt, after.lastChangedAt) // unchanged
    }

    @Test
    fun `a status change is flagged changed and bumps last_changed_at`() {
        val rid = reservable()
        val date = LocalDate.now().plusDays(1)
        val repo = repo()
        repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.AVAILABLE, Instant.now())))
        val before = repo.loadCells(listOf(rid), listOf(date)).single()
        Thread.sleep(5)
        val result = repo.upsertObservations(listOf(AvailabilityCellRepo.CellObservation(rid, date, AvailabilityStatus.RESERVED, Instant.now())))
        assertTrue(result.single().changed)
        val cell = repo.loadCells(listOf(rid), listOf(date)).single()
        assertEquals(AvailabilityStatus.RESERVED, cell.status)
        assertTrue(cell.lastChangedAt.isAfter(before.lastChangedAt))
    }

    @Test
    fun `markElapsedAsPast flips only target_date before today and only once`() {
        val rid = reservable()
        val repo = repo()
        val yesterday = LocalDate.now().minusDays(1)
        val today = LocalDate.now()
        repo.upsertObservations(
            listOf(
                AvailabilityCellRepo.CellObservation(rid, yesterday, AvailabilityStatus.AVAILABLE, Instant.now()),
                AvailabilityCellRepo.CellObservation(rid, today, AvailabilityStatus.AVAILABLE, Instant.now()),
            ),
        )
        val updated = repo.markElapsedAsPast(listOf(rid), today)
        assertEquals(1, updated)
        assertEquals(AvailabilityStatus.PAST, repo.loadCells(listOf(rid), listOf(yesterday)).single().status)
        assertEquals(AvailabilityStatus.AVAILABLE, repo.loadCells(listOf(rid), listOf(today)).single().status)
        assertEquals(0, repo.markElapsedAsPast(listOf(rid), today)) // idempotent, no double-flip
    }
}
