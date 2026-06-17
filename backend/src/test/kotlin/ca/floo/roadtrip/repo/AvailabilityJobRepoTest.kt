package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityJobRepoTest {
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
        ctx.execute("DELETE FROM availability_job")
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

    private fun seedWatch(poiId: Long): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO availability_watch (
                    poi_id, start_date, end_date, cadence_sec, trigger_kinds
                ) VALUES (
                    ?, '2026-07-04'::date, '2026-07-05'::date, 60, ARRAY['atc']
                ) RETURNING id
                """.trimIndent(),
                poiId,
            )!!
            .get("id", Long::class.java)

    private val sampleIntent: JsonObject =
        buildJsonObject {
            put("kind", JsonPrimitive("reservable_window"))
            put("rid", JsonPrimitive("site:recgov:330257"))
        }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    @Test
    fun `upsertForWatch creates a new job`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        val job = repo.upsertForWatch(watchId, sampleIntent, 60, "active", now())
        assertEquals(watchId, job.watchId)
        assertEquals(60, job.cadenceSec)
        assertEquals("active", job.status)
        assertEquals(sampleIntent, job.intentPayload)
    }

    @Test
    fun `upsertForWatch is idempotent on watch_id`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        val first = repo.upsertForWatch(watchId, sampleIntent, 60, "active", now())
        val second = repo.upsertForWatch(watchId, sampleIntent, 120, "paused", now())
        assertEquals(first.id, second.id)
        assertEquals(120, second.cadenceSec)
        assertEquals("paused", second.status)
    }

    @Test
    fun `claimDue returns active jobs whose nextRunAt has passed`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        val past = now().minusMinutes(1)
        repo.upsertForWatch(watchId, sampleIntent, 60, "active", past)
        val claimed = repo.claimDue(now(), limit = 10, leaseDuration = Duration.ofSeconds(30))
        assertEquals(1, claimed.size)
        assertNotNull(claimed[0].claimToken)
        assertNotNull(claimed[0].claimedUntil)
    }

    @Test
    fun `claimDue skips paused and future jobs`() {
        val poiId = seedPoi()
        val activeWatch = seedWatch(poiId)
        val pausedWatchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        poi_id, start_date, end_date, cadence_sec, trigger_kinds, status
                    ) VALUES (
                        ?, '2026-07-04'::date, '2026-07-05'::date, 60, ARRAY['atc'], 'paused'
                    ) RETURNING id
                    """.trimIndent(),
                    poiId,
                )!!
                .get("id", Long::class.java)
        val repo = AvailabilityJobRepo(ctx)
        repo.upsertForWatch(activeWatch, sampleIntent, 60, "active", now().minusSeconds(5))
        repo.upsertForWatch(pausedWatchId, sampleIntent, 60, "paused", now().minusSeconds(5))
        val futureWatchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        poi_id, start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?, '2026-07-04'::date, '2026-07-05'::date, 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    poiId,
                )!!
                .get("id", Long::class.java)
        repo.upsertForWatch(futureWatchId, sampleIntent, 60, "active", now().plusMinutes(1))
        val claimed = repo.claimDue(now(), limit = 10, leaseDuration = Duration.ofSeconds(30))
        assertEquals(1, claimed.size)
        assertEquals(activeWatch, claimed[0].watchId)
    }

    @Test
    fun `release advances nextRunAt only with matching token`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        repo.upsertForWatch(watchId, sampleIntent, 60, "active", now().minusMinutes(1))
        val claimed = repo.claimDue(now(), limit = 1, leaseDuration = Duration.ofSeconds(30))[0]
        val nextRun = now().plusMinutes(1)
        assertTrue(repo.release(claimed.id, claimed.claimToken!!, nextRun, now()))
        val after = repo.findById(claimed.id)!!
        assertNull(after.claimToken)
        assertNull(after.claimedUntil)
        assertEquals(nextRun.toEpochSecond(), after.nextRunAt.toEpochSecond())
        // Wrong token: no-op.
        assertFalse(repo.release(claimed.id, "wrong-token", nextRun.plusMinutes(1), now()))
    }

    @Test
    fun `reclaimExpired clears expired leases`() {
        val watchId = seedWatch(seedPoi())
        val repo = AvailabilityJobRepo(ctx)
        val baseTime = now()
        repo.upsertForWatch(watchId, sampleIntent, 60, "active", baseTime.minusMinutes(2))
        repo.claimDue(baseTime.minusMinutes(1), limit = 1, leaseDuration = Duration.ofSeconds(10))
        val reclaimed = repo.reclaimExpired(baseTime)
        assertEquals(1, reclaimed)
        val after = repo.findByWatchId(watchId)!!
        assertNull(after.claimToken)
    }
}
