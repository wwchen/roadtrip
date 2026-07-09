package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvailabilityRunRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedPoi(): Long = ctx.seedCatalogPoi(sourceId = "p1", name = "Upper Pines", lon = -119.56, lat = 37.74).poiId

    /** Seeds a poller for (recgov, 232447) rooted at a fresh poi. Returns its id. */
    private fun seedPoller(): Long =
        AvailabilityPollerRepo(ctx).upsertActive(
            provider = "recgov",
            parentRef = "232447",
            poiId = seedPoi(),
            pullNextRunAt = null,
        )

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `start creates a row in 'started' state`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        val started = now()
        val runId = repo.start(pollerId, started)
        val row = repo.findById(runId)
        assertNotNull(row)
        assertEquals(pollerId, row.pollerId)
        assertEquals("started", row.status)
        assertEquals(0, row.snapshotCount)
        assertNull(row.durationMs)
        assertNull(row.completedAt)
        assertNull(row.error)
        assertEquals(started.toEpochSecond(), row.startedAt.toEpochSecond())
    }

    @Test
    fun `complete updates a started row and returns true`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        val runId = repo.start(pollerId, now().minusSeconds(2))
        val ok = repo.complete(runId, snapshotCount = 7, completedAt = now(), durationMs = 1234)
        assertTrue(ok)
        val row = repo.findById(runId)!!
        assertEquals("completed", row.status)
        assertEquals(7, row.snapshotCount)
        assertEquals(1234, row.durationMs)
        assertNotNull(row.completedAt)
        assertNull(row.error)
    }

    @Test
    fun `complete is idempotent — second call returns false`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        val runId = repo.start(pollerId, now().minusSeconds(2))
        assertTrue(repo.complete(runId, snapshotCount = 1, completedAt = now(), durationMs = 100))
        // Second call: row is no longer 'started', so update returns 0 rows.
        assertFalse(repo.complete(runId, snapshotCount = 99, completedAt = now(), durationMs = 999))
        // Original values preserved.
        val row = repo.findById(runId)!!
        assertEquals(1, row.snapshotCount)
        assertEquals(100, row.durationMs)
    }

    @Test
    fun `fail updates a started row with error and returns true`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        val runId = repo.start(pollerId, now().minusSeconds(2))
        val ok = repo.fail(runId, error = "upstream 503", completedAt = now(), durationMs = 5000)
        assertTrue(ok)
        val row = repo.findById(runId)!!
        assertEquals("failed", row.status)
        assertEquals("upstream 503", row.error)
        assertEquals(5000, row.durationMs)
        assertEquals(0, row.snapshotCount)
    }

    @Test
    fun `listForPoller returns runs newest-first`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        val r1 = repo.start(pollerId, now().minusMinutes(3))
        repo.complete(r1, 1, now().minusMinutes(2), 100)
        val r2 = repo.start(pollerId, now().minusMinutes(1))
        repo.complete(r2, 2, now(), 100)
        val rows = repo.listForPoller(pollerId, limit = 10)
        assertEquals(2, rows.size)
        assertEquals(r2, rows[0].id)
        assertEquals(r1, rows[1].id)
    }

    @Test
    fun `countConsecutiveFailures counts leading failed runs`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        // oldest → newest: completed, failed, failed
        repo.start(pollerId, now().minusMinutes(3)).also { repo.complete(it, 1, now().minusMinutes(3), 10) }
        repo.start(pollerId, now().minusMinutes(2)).also { repo.fail(it, "rate_limited", now().minusMinutes(2), 10) }
        repo.start(pollerId, now().minusMinutes(1)).also { repo.fail(it, "rate_limited", now().minusMinutes(1), 10) }
        assertEquals(2, repo.countConsecutiveFailures(pollerId))
    }

    @Test
    fun `countConsecutiveFailures is zero when newest run completed`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        repo.start(pollerId, now().minusMinutes(1)).also { repo.fail(it, "x", now().minusMinutes(1), 10) }
        repo.start(pollerId, now()).also { repo.complete(it, 1, now(), 10) }
        assertEquals(0, repo.countConsecutiveFailures(pollerId))
    }

    @Test
    fun `countConsecutiveFailures is zero when there are no terminal runs`() {
        val pollerId = seedPoller()
        val repo = AvailabilityRunRepo(ctx)
        repo.start(pollerId, now())
        assertEquals(0, repo.countConsecutiveFailures(pollerId))
    }
}
