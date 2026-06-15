package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityWatchRoutesTest {
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
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    @Test
    fun `POST creates a poi-scoped watch with filters`() =
        testApplication {
            application { routing { availabilityWatchRoutes(ctx) } }
            val poiId = seedPoi(sourceId = "p1", name = "Upper Pines")
            val body =
                """
                {
                  "poi_id": $poiId,
                  "reservable_filters": {"loop": ["A"]},
                  "target_dates": ["2026-07-04", "2026-07-05"],
                  "min_nights": 2,
                  "cadence_sec": 60,
                  "trigger_kinds": ["atc"]
                }
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals(poiId, obj["poi_id"]!!.jsonPrimitive.long)
            assertEquals(2, obj["target_dates"]!!.jsonArray.size)
            assertEquals("active", obj["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects missing scope`() =
        testApplication {
            application { routing { availabilityWatchRoutes(ctx) } }
            val body =
                """
                {"target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET list filters by status`() =
        testApplication {
            application { routing { availabilityWatchRoutes(ctx) } }
            val poiId = seedPoi(sourceId = "p2", name = "Glacier")
            val body =
                """
                {"poi_id": $poiId, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            repeat(3) {
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val resp = client.get("/api/availability/watches?status=active")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(3, obj["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `PATCH pauses a watch`() =
        testApplication {
            application { routing { availabilityWatchRoutes(ctx) } }
            val poiId = seedPoi(sourceId = "p3", name = "Yosemite")
            val body =
                """
                {"poi_id": $poiId, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val resp =
                client.patch("/api/availability/watches/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "paused"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals("paused", obj["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `DELETE removes a watch`() =
        testApplication {
            application { routing { availabilityWatchRoutes(ctx) } }
            val poiId = seedPoi(sourceId = "p4", name = "Tunnel")
            val body =
                """
                {"poi_id": $poiId, "target_dates": ["2026-07-04"], "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val del = client.delete("/api/availability/watches/$id")
            assertEquals(HttpStatusCode.NoContent, del.status)
            val getAfter = client.get("/api/availability/watches/$id")
            assertEquals(HttpStatusCode.NotFound, getAfter.status)
        }

    private fun seedPoi(
        sourceId: String,
        name: String,
        providerRefJson: String? = null,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom,
                    region, properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', ?,
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                )
                RETURNING id
                """.trimIndent(),
                sourceId,
                name,
                providerRefJson,
            )!!
            .get("id", Long::class.java)
}
