package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.references.API_CACHE
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiCacheRepoTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    private class TestClock(
        private var instant: Instant,
    ) : Clock() {
        override fun instant(): Instant = instant

        override fun withZone(zone: ZoneId): Clock = this

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }
    }

    @BeforeAll
    fun start() {
        System.setProperty("api.version", "1.44")
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_api_cache")
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
        ctx = dsl(ds)
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun reset() {
        ctx.deleteFrom(API_CACHE).execute()
    }

    @Test
    fun `stores jsonb payloads and expires them by ttl`() {
        val clock = TestClock(Instant.parse("2026-06-09T12:00:00Z"))
        val repo = ApiCacheRepo(ctx, clock)
        val payload =
            buildJsonObject {
                put("status", "cached")
                put("count", 2)
            }

        repo.put("unit", "example", payload, Duration.ofMinutes(5))
        val hit = repo.get("unit", "example")
        val status =
            hit
                ?.payload
                ?.jsonObject
                ?.get("status")
                ?.jsonPrimitive
                ?.content

        assertEquals(payload, hit?.payload)
        assertEquals("cached", status)
        assertEquals(300, hit?.ttlSeconds())

        clock.advance(Duration.ofMinutes(6))

        assertNull(repo.get("unit", "example"))
        assertEquals(0, ctx.fetchCount(API_CACHE))
    }
}
