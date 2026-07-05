package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
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
        assertEquals(0, transitions)
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
        assertEquals(1, transitions)
        assertEquals(2, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(rid), listOf(date)).single()
        assertEquals(AvailabilityStatus.AVAILABLE, current.status)
    }
}
