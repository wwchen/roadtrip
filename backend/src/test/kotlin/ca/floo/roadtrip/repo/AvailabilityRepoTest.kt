package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilityRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedCampsite(vendorId: String): Long =
        ctx.seedCampsite(
            campgroundId =
                ctx.seedCampground(
                    source = "recgov",
                    sourceId = "cg-$vendorId",
                    providerRefJson = """{"recgov_id":"cg-$vendorId"}""",
                ),
            vendor = "recgov",
            vendorId = vendorId,
            name = "site",
        )

    private val date = LocalDate.parse("2026-07-04")

    @Test
    fun `unchanged status bumps last_observed_at in place, no new row`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:05:00Z")
        repo.recordObservations(runId = null, listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, t1)))
        val transitions =
            repo.recordObservations(
                runId = null,
                listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, t2)),
            )
        assertEquals(0, transitions.size)
        assertEquals(1, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(campsiteId), listOf(date)).single()
        assertEquals(t2, current.observedAt.toInstant())
    }

    @Test
    fun `status change inserts a new row linked by previous_id`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:05:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, t1)))
        val transitions =
            repo.recordObservations(
                null,
                listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.AVAILABLE, t2)),
            )
        assertEquals(1, transitions.size)
        assertEquals(2, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(campsiteId), listOf(date)).single()
        assertEquals(AvailabilityStatus.AVAILABLE, current.status)
    }

    @Test
    fun `markElapsedAsPast adds a past status-run for elapsed cells only`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val past = LocalDate.parse("2026-06-01")
        val future = LocalDate.parse("2026-08-01")
        val t = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(
            null,
            listOf(
                AvailabilityRepo.Observation(campsiteId, past, AvailabilityStatus.RESERVED, t),
                AvailabilityRepo.Observation(campsiteId, future, AvailabilityStatus.RESERVED, t),
            ),
        )
        val inserted = repo.markElapsedAsPast(listOf(campsiteId), today = LocalDate.parse("2026-07-04"))
        assertEquals(1, inserted)
        assertEquals(AvailabilityStatus.PAST, repo.readCurrent(listOf(campsiteId), listOf(past)).single().status)
        assertEquals(AvailabilityStatus.RESERVED, repo.readCurrent(listOf(campsiteId), listOf(future)).single().status)
    }

    @Test
    fun `markElapsedAsPast is idempotent - an already-past cell is not re-marked`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val past = LocalDate.parse("2026-06-01")
        val t = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(campsiteId, past, AvailabilityStatus.RESERVED, t)))
        val today = LocalDate.parse("2026-07-04")
        assertEquals(1, repo.markElapsedAsPast(listOf(campsiteId), today = today))
        // Second run must not insert a duplicate past-run (would violate the previous_id chain).
        assertEquals(0, repo.markElapsedAsPast(listOf(campsiteId), today = today))
        assertEquals(2, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
    }

    @Test
    fun `hasFreshCoverage requires every campsite-date cell to be recent`() {
        val campsiteA = seedCampsite("100")
        val campsiteB = seedCampsite("101")
        val repo = AvailabilityRepo(ctx)
        val startDate = LocalDate.parse("2026-07-04")
        val endDate = startDate.plusDays(2)
        val now = Instant.parse("2026-06-18T10:00:00Z")
        val cutoff = OffsetDateTime.ofInstant(now.minus(Duration.ofMinutes(5)), ZoneOffset.UTC)

        repo.recordObservations(
            null,
            listOf(
                AvailabilityRepo.Observation(campsiteA, startDate, AvailabilityStatus.RESERVED, now),
                AvailabilityRepo.Observation(campsiteA, startDate.plusDays(1), AvailabilityStatus.RESERVED, now),
                AvailabilityRepo.Observation(campsiteB, startDate, AvailabilityStatus.RESERVED, now),
            ),
        )

        assertFalse(repo.hasFreshCoverage(listOf(campsiteA, campsiteB), startDate, endDate, cutoff))

        repo.recordObservations(
            null,
            listOf(
                AvailabilityRepo.Observation(campsiteB, startDate.plusDays(1), AvailabilityStatus.RESERVED, now),
            ),
        )

        assertTrue(repo.hasFreshCoverage(listOf(campsiteA, campsiteB), startDate, endDate, cutoff))
        assertTrue(
            repo.hasFreshCoverage(
                listOf(campsiteA, campsiteB),
                startDate,
                endDate,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            ),
        )
        assertFalse(
            repo.hasFreshCoverage(
                listOf(campsiteA, campsiteB),
                startDate,
                endDate,
                OffsetDateTime.ofInstant(now.plus(Duration.ofSeconds(1)), ZoneOffset.UTC),
            ),
        )
    }

    @Test
    fun `history walks the previous_id chain`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T11:00:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, t1)))
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.AVAILABLE, t2)))
        val runs = repo.listForCampsite(campsiteId)
        assertEquals(2, runs.size)
        val current = runs.first { it.toStatus == AvailabilityStatus.AVAILABLE }
        assertEquals(AvailabilityStatus.RESERVED, current.fromStatus)
    }
}
