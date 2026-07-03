package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityRunRepoTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun start() {
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_test")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                maximumPoolSize = 2
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_run")
        ctx.execute("DELETE FROM availability_watch_poller")
        ctx.execute("DELETE FROM availability_poller")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun seedPoi(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', 'p1', 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, NULL, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
            )!!
            .get("id", Long::class.java)

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
