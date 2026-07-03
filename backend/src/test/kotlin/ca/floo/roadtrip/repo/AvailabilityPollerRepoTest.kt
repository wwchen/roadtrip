package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityPoller.Companion.AVAILABILITY_POLLER
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
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityPollerRepoTest {
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

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    private fun insertPoi(): Long =
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

    /** Inserts an `availability_watch` row that is active with a future end_date. */
    private fun insertActiveWatch(
        poiId: Long,
        startDate: String = "2026-07-04",
        endDate: String = "2026-12-31",
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO availability_watch (
                    poi_id, start_date, end_date, cadence_sec, trigger_kinds
                ) VALUES (
                    ?, ?::date, ?::date, 60, ARRAY['atc']
                ) RETURNING id
                """.trimIndent(),
                poiId,
                startDate,
                endDate,
            )!!
            .get("id", Long::class.java)

    /** Inserts a paused watch (excluded from `liveWatchesForPoller`). */
    private fun insertPausedWatch(
        poiId: Long,
        startDate: String = "2026-07-04",
        endDate: String = "2026-12-31",
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO availability_watch (
                    poi_id, start_date, end_date, cadence_sec, trigger_kinds, status
                ) VALUES (
                    ?, ?::date, ?::date, 60, ARRAY['atc'], 'paused'
                ) RETURNING id
                """.trimIndent(),
                poiId,
                startDate,
                endDate,
            )!!
            .get("id", Long::class.java)

    /** Inserts an active watch whose end_date is already in the past (elapsed). */
    private fun insertElapsedWatch(poiId: Long): Long = insertActiveWatch(poiId, startDate = "2020-01-01", endDate = "2020-01-02")

    private fun watchStatus(watchId: Long): String =
        ctx
            .fetchOne("SELECT status FROM availability_watch WHERE id = ?", watchId)!!
            .get("status", String::class.java)

    /** Test-only helper: parks a poller's next_run_at far in the future so claimDue skips it. */
    private fun AvailabilityPollerRepo.parkFar(pollerId: Long) {
        ctx
            .update(AVAILABILITY_POLLER)
            .set(AVAILABILITY_POLLER.NEXT_RUN_AT, now().plusDays(30))
            .where(AVAILABILITY_POLLER.ID.eq(pollerId))
            .execute()
    }

    @Test
    fun `upsertActive inserts once per provider+parentRef and revives a dormant poller`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val id1 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = null)
        val id2 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = null)
        assertEquals(id1, id2) // UNIQUE(provider,parent_ref)
        repo.deactivatePollersWithNoLinks() // no links -> dormant
        assertFalse(repo.findById(id1)!!.active)
        val id3 = repo.upsertActive("recgov", "232447", poi, pullNextRunAt = OffsetDateTime.now())
        assertEquals(id1, id3)
        assertTrue(repo.findById(id1)!!.active) // revived
    }

    @Test
    fun `claimDue returns only active due pollers and leases them`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val due = repo.upsertActive("recgov", "A", poi, null)
        val notDue = repo.upsertActive("recgov", "B", poi, null)
        repo.parkFar(notDue) // test helper via release far-future
        val claimed = repo.claimDue(OffsetDateTime.now(), limit = 10, leaseDuration = Duration.ofMinutes(2))
        assertEquals(listOf(due), claimed.map { it.id })
        assertNotNull(claimed.single().claimToken)
    }

    @Test
    fun `retire marks watches done, drops links, deactivates poller`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val watch = insertActiveWatch(poiId = poi) // helper inserts availability_watch row
        val poller = repo.upsertActive("recgov", "A", poi, null)
        repo.linkWatch(watch, poller)
        repo.retire(poller, elapsedWatchIds = listOf(watch))
        assertFalse(repo.findById(poller)!!.active)
        assertTrue(repo.pollerIdsForWatch(watch).isEmpty())
        assertEquals("done", watchStatus(watch)) // helper reads availability_watch.status
    }

    @Test
    fun `liveWatchesForPoller returns active watches with a future end_date only`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)

        val live = insertActiveWatch(poiId = poi)
        val paused = insertPausedWatch(poiId = poi)
        val elapsed = insertElapsedWatch(poiId = poi)

        repo.linkWatch(live, poller)
        repo.linkWatch(paused, poller)
        repo.linkWatch(elapsed, poller)

        val result = repo.liveWatchesForPoller(poller)
        assertEquals(listOf(live), result.map { it.id })
    }

    @Test
    fun `watchIdsForPoller returns all linked watch ids regardless of status`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, null)

        val live = insertActiveWatch(poiId = poi)
        val paused = insertPausedWatch(poiId = poi)

        repo.linkWatch(live, poller)
        repo.linkWatch(paused, poller)

        assertEquals(setOf(live, paused), repo.watchIdsForPoller(poller).toSet())
    }

    @Test
    fun `release advances nextRunAt only with matching token`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val poller = repo.upsertActive("recgov", "A", poi, now().minusMinutes(1))
        val claimed = repo.claimDue(now(), limit = 1, leaseDuration = Duration.ofSeconds(30))[0]
        val nextRun = now().plusMinutes(1)
        assertTrue(repo.release(claimed.id, claimed.claimToken!!, nextRun, now()))
        val after = repo.findById(claimed.id)!!
        assertEquals(null, after.claimToken)
        assertEquals(nextRun.toEpochSecond(), after.nextRunAt.toEpochSecond())
        // Wrong token: no-op.
        assertFalse(repo.release(claimed.id, "wrong-token", nextRun.plusMinutes(1), now()))
    }

    @Test
    fun `reclaimExpired clears expired leases`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val baseTime = now()
        repo.upsertActive("recgov", "A", poi, baseTime.minusMinutes(2))
        repo.claimDue(baseTime.minusMinutes(1), limit = 1, leaseDuration = Duration.ofSeconds(10))
        val reclaimed = repo.reclaimExpired(baseTime)
        assertEquals(1, reclaimed)
    }

    @Test
    fun `pollerIdsForWatch reflects linkWatch and replaceLinksForWatch`() {
        val repo = AvailabilityPollerRepo(ctx)
        val poi = insertPoi()
        val watch = insertActiveWatch(poiId = poi)
        val pollerA = repo.upsertActive("recgov", "A", poi, null)
        val pollerB = repo.upsertActive("recgov", "B", poi, null)

        repo.linkWatch(watch, pollerA)
        assertEquals(listOf(pollerA), repo.pollerIdsForWatch(watch))

        repo.replaceLinksForWatch(watch, setOf(pollerB))
        assertEquals(listOf(pollerB), repo.pollerIdsForWatch(watch))
    }
}
