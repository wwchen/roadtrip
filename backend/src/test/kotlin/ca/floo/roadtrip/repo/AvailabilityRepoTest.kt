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
import kotlin.test.assertNull
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
    fun `a fresh not-bookable cell is the only thing that blocks`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val observedAt = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(
            runId = null,
            listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, observedAt)),
        )

        assertEquals(
            setOf(date),
            repo.freshlyUnavailableDates(
                campsiteId,
                listOf(date),
                Duration.ofMinutes(30),
                OffsetDateTime.parse("2026-06-18T10:05:00Z"),
            ),
        )
    }

    @Test
    fun `a stale not-bookable cell no longer blocks`() {
        // The age bound decides what still counts as EVIDENCE, not what counts
        // as permission. Twenty minutes on against a five minute bound, we no
        // longer know, so the vendor decides.
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        repo.recordObservations(
            runId = null,
            listOf(
                AvailabilityRepo.Observation(
                    campsiteId,
                    date,
                    AvailabilityStatus.RESERVED,
                    Instant.parse("2026-06-18T10:00:00Z"),
                ),
            ),
        )

        assertEquals(
            emptySet(),
            repo.freshlyUnavailableDates(
                campsiteId,
                listOf(date),
                Duration.ofMinutes(5),
                OffsetDateTime.parse("2026-06-18T10:20:00Z"),
            ),
        )
    }

    @Test
    fun `a stale AVAILABLE cell does not block — the live bug`() {
        // A genuinely bookable site was refused as "not available" because its
        // observation, which said AVAILABLE, was eight minutes old. This table
        // is filled by the watch poller, so an unwatched site is stale within
        // minutes of the grid loading; silence must never read as "taken".
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        repo.recordObservations(
            runId = null,
            listOf(
                AvailabilityRepo.Observation(
                    campsiteId,
                    date,
                    AvailabilityStatus.AVAILABLE,
                    Instant.parse("2026-06-18T10:00:00Z"),
                ),
            ),
        )

        assertEquals(
            emptySet(),
            repo.freshlyUnavailableDates(
                campsiteId,
                listOf(date),
                Duration.ofMinutes(5),
                OffsetDateTime.parse("2026-06-18T10:20:00Z"),
            ),
        )
    }

    @Test
    fun `a night never observed does not block`() {
        val campsiteId = seedCampsite("100")

        assertEquals(
            emptySet(),
            AvailabilityRepo(ctx).freshlyUnavailableDates(
                campsiteId,
                listOf(date),
                Duration.ofMinutes(30),
                OffsetDateTime.now(),
            ),
        )
    }

    // `availability.run_id` is nullable, so reading it with the JVM primitive
    // class reports run 0 instead of "no run".
    @Test
    fun `an observation recorded outside a run reports no run id, not run zero`() {
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        repo.recordObservations(
            runId = null,
            listOf(
                AvailabilityRepo.Observation(
                    campsiteId,
                    date,
                    AvailabilityStatus.AVAILABLE,
                    Instant.parse("2026-06-18T10:00:00Z"),
                ),
            ),
        )

        assertNull(repo.listForCampsite(campsiteId).single().runId)
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
    fun `a cell named twice in one call is one observation, the latest`() {
        // A batch can mention a cell twice — two payloads covering one day, a
        // vendor repeating a resource. That is one observation of that cell, not
        // a status run from one to the other: nothing was seen to change in the
        // instant between them.
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        val t2 = Instant.parse("2026-06-18T10:00:01Z")

        val transitions =
            repo.recordObservations(
                null,
                listOf(
                    AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, t1),
                    AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.AVAILABLE, t2),
                ),
            )

        assertEquals(1, transitions.size)
        assertEquals(AvailabilityStatus.AVAILABLE, transitions.single().status)
        assertEquals(1, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
        val current = repo.readCurrent(listOf(campsiteId), listOf(date)).single()
        assertEquals(AvailabilityStatus.AVAILABLE, current.status)
        assertEquals(t2, current.observedAt.toInstant())
    }

    @Test
    fun `a cell whose chain already has a successor is left alone, not failed`() {
        // Two writers to one cell — a drawer read and a poll tick, or two
        // differently-filtered checks of the same campground, which now cover
        // the same cells — can both chain a new run onto the row they each read
        // as current. The second one's row is redundant, not an error: it says
        // what the first already said, moments apart. Failing the transaction
        // would fail a whole campground's write, and with it the request.
        //
        // The state is built by hand because the window it opens in practice is
        // a few milliseconds wide: `successor` is the row the other writer
        // inserted, dated behind `current` so `current` is still the one this
        // write picks up.
        val campsiteId = seedCampsite("100")
        val repo = AvailabilityRepo(ctx)
        val t1 = Instant.parse("2026-06-18T10:00:00Z")
        repo.recordObservations(null, listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.RESERVED, t1)))
        val currentId = currentRowId(campsiteId)
        insertStatusRun(
            campsiteId = campsiteId,
            status = "available",
            lastObservedAt = t1.minusSeconds(1),
            previousId = currentId,
        )

        val transitions =
            repo.recordObservations(
                null,
                listOf(AvailabilityRepo.Observation(campsiteId, date, AvailabilityStatus.AVAILABLE, t1.plusSeconds(60))),
            )

        assertEquals(emptyList(), transitions, "the run the other writer already inserted is not this write's transition")
        assertEquals(2, ctx.fetchCount(ctx.selectFrom(ca.floo.roadtrip.db.generated.tables.Availability.AVAILABILITY)))
    }

    private fun currentRowId(campsiteId: Long): Long =
        ctx
            .fetchOne("SELECT id FROM availability WHERE campsite_id = ?", campsiteId)!!
            .get("id", Long::class.java)

    private fun insertStatusRun(
        campsiteId: Long,
        status: String,
        lastObservedAt: Instant,
        previousId: Long,
    ) {
        ctx
            .query(
                """
                INSERT INTO availability (campsite_id, target_date, status, last_observed_at, previous_id)
                VALUES (?, ?::date, ?::availability_status, ?::timestamptz, ?)
                """.trimIndent(),
                campsiteId,
                date,
                status,
                OffsetDateTime.ofInstant(lastObservedAt, ZoneOffset.UTC),
                previousId,
            ).execute()
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
