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
    private val date = LocalDate.parse("2026-07-04")

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
    fun `availableDates drops a cell older than the age bound`() {
        // readCurrent happily returns a year-old `available` — it is still the
        // newest row. A booking gate that trusted it would drive a browser on
        // the strength of last season's observation.
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val observedAt = Instant.parse("2026-06-18T10:00:00Z")
        val now = OffsetDateTime.parse("2026-06-18T10:20:00Z")
        repo.recordObservations(
            runId = null,
            listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.AVAILABLE, observedAt)),
        )

        assertEquals(
            setOf(date),
            repo.availableDates(campsiteId, listOf(date), Duration.ofMinutes(30), now),
        )
        assertEquals(
            emptySet(),
            repo.availableDates(campsiteId, listOf(date), Duration.ofMinutes(5), now),
            "20 minutes old against a 5 minute bound is not evidence the site is free",
        )
    }

    @Test
    fun `availableDates drops a fresh cell that is not bookable`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val observedAt = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(
            runId = null,
            listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, observedAt)),
        )

        assertEquals(
            emptySet(),
            repo.availableDates(campsiteId, listOf(date), Duration.ofMinutes(30), OffsetDateTime.parse("2026-06-18T10:05:00Z")),
        )
    }

    @Test
    fun `availableDates counts a night never observed as unavailable`() {
        val campsiteId = seedCampsite("100")

        assertEquals(
            emptySet(),
            AvailabilityRepo(ctx).availableDates(campsiteId, listOf(date), Duration.ofMinutes(30), OffsetDateTime.now()),
        )
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
