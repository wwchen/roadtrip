package ca.floo.roadtrip.route

import ca.floo.roadtrip.config.DbConfig
import ca.floo.roadtrip.db.dataSourceFor
import ca.floo.roadtrip.repo.DatabaseHealthRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.service.health.ReadinessServiceImpl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pool exhaustion is the incident `/api/health/ready` exists to report, so the
 * probe has to stay fast during precisely that failure.
 *
 * jOOQ's `queryTimeout` cannot deliver that on its own: its clock starts after a
 * JDBC connection has been handed over, so with Hikari's default 30-second
 * [hikariDefaultConnectionTimeout] a readiness request blocks for half a
 * minute in the queue and the orchestrator's tight polling loop piles up behind
 * it. This drives a real pool whose only connection is held elsewhere and
 * asserts the endpoint answers 503 well inside [promptResponseBudget].
 */
class ReadinessPoolExhaustionTest : SharedDbTest() {
    @Test
    fun `readiness answers 503 promptly when the pool is exhausted instead of blocking on acquisition`() {
        val exhaustedPool =
            dataSourceFor(
                DbConfig(
                    jdbcUrl = ds.jdbcUrl,
                    user = ds.username,
                    password = ds.password,
                    maxPoolSize = 1,
                    connectionTimeout = testConnectionTimeout,
                ),
            )

        exhaustedPool.use { pool ->
            val readiness = ReadinessServiceImpl(DatabaseHealthRepo(DSL.using(pool, SQLDialect.POSTGRES)))

            // Hold the pool's only connection for the whole request, so the
            // probe cannot get one and must time out on acquisition.
            pool.connection.use {
                testApplication {
                    application { routing { healthRoutes(readiness) } }

                    val startedAt = System.nanoTime()
                    val response = client.get("/api/health/ready")
                    val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

                    assertEquals(
                        HttpStatusCode.ServiceUnavailable,
                        response.status,
                        "an exhausted pool means this instance cannot serve traffic",
                    )
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals("not_ready", body["status"]!!.jsonPrimitive.content)
                    assertEquals("down", body["database"]!!.jsonPrimitive.content)
                    assertTrue(
                        elapsed < promptResponseBudget,
                        "readiness took $elapsed; it must be bounded by the configured " +
                            "connection timeout, not Hikari's $hikariDefaultConnectionTimeout default",
                    )
                    // Names the mechanism behind the timing assertion above, so a
                    // failure here reads as "the config stopped reaching Hikari".
                    assertEquals(testConnectionTimeout.toMillis(), pool.connectionTimeout)
                }
            }
        }
    }

    @Test
    fun `the shipped default bounds connection acquisition far below Hikari's own default`() {
        assertTrue(
            DbConfig.defaultConnectionTimeout < hikariDefaultConnectionTimeout,
            "leaving Hikari's default in place is the bug this default exists to prevent",
        )
    }

    private companion object {
        /** What HikariCP waits for a connection when nothing configures it. */
        val hikariDefaultConnectionTimeout: Duration = Duration.ofSeconds(30)

        /** Short enough to keep the test quick, above Hikari's 250ms floor. */
        val testConnectionTimeout: Duration = Duration.ofMillis(500)

        /**
         * Generous next to [testConnectionTimeout] so a loaded CI runner does
         * not flake, but far enough under [hikariDefaultConnectionTimeout]
         * that an unbounded pool wait cannot pass.
         */
        val promptResponseBudget: Duration = Duration.ofSeconds(10)
    }
}
