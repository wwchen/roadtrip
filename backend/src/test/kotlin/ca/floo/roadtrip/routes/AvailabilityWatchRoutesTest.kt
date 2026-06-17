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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        ctx.execute("DELETE FROM availability_job")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    @Test
    fun `POST creates a poi-scoped watch with filters`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p1", name = "Upper Pines")
            val body =
                """
                {
                  "poi_id": $poiId,
                  "reservable_filters": {"loop": ["A"]},
                  "start_date": "2026-07-04",
                  "end_date": "2026-07-06",
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
            assertEquals("2026-07-04", obj["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-06", obj["end_date"]!!.jsonPrimitive.content)
            assertEquals(false, obj.containsKey("target_dates"))
            assertEquals(false, obj.containsKey("min_nights"))
            assertEquals("active", obj["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects invalid date window`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-invalid-window", name = "Invalid Window")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-06", "end_date": "2026-07-04", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_date_window", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects removed date fields`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-removed-create", name = "Removed Create")
            val body =
                """
                {
                  "poi_id": $poiId,
                  "start_date": "2026-07-04",
                  "end_date": "2026-07-06",
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
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("removed_fields", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects missing scope`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val body =
                """
                {"start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
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
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p2", name = "Glacier")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
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
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p3", name = "Yosemite")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
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
    fun `PATCH rejects removed date fields`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-removed-patch", name = "Removed Patch")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
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
                    setBody("""{"target_dates": ["2026-07-04"], "min_nights": 1}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("removed_fields", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `DELETE removes a watch`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p4", name = "Tunnel")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
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

    @Test
    fun `POST creates a job and PATCH paused parks it`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p99", name = "Atomic")
            val createBody =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            val watchId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val jobs =
                ca.floo.roadtrip.repo
                    .AvailabilityJobRepo(ctx)
            val job = jobs.findByWatchId(watchId)
            assertNotNull(job)
            assertEquals(60, job.cadenceSec)
            assertEquals("active", job.status)

            val paused =
                client.patch("/api/availability/watches/$watchId") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "paused"}""")
                }
            assertEquals(HttpStatusCode.OK, paused.status)
            val pausedJob = jobs.findByWatchId(watchId)!!
            assertEquals("paused", pausedJob.status)
            assertTrue(pausedJob.nextRunAt.year >= 9990, "nextRunAt should be parked in far future, got ${pausedJob.nextRunAt}")
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

    private fun seedReservable(
        vendorId: String,
        name: String? = null,
        loop: String? = null,
        siteType: String? = null,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name, loop, site_type
                ) VALUES (
                    'site', 'recgov', ?, 'federal-campsites', ?, ?, ?
                ) RETURNING id
                """.trimIndent(),
                vendorId,
                name,
                loop,
                siteType,
            )!!
            .get("id", Long::class.java)

    private fun linkReservableToPoi(
        reservableId: Long,
        poiId: Long,
    ) {
        ctx.execute(
            "INSERT INTO reservable_pois (reservable_id, poi_id) VALUES (?, ?)",
            reservableId,
            poiId,
        )
    }

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: String,
        observedAt: java.time.OffsetDateTime,
        available: Boolean,
    ) {
        ctx.execute(
            """
            INSERT INTO availability_snapshot (
                reservable_id, observed_at, target_date, status, available, day_payload
            ) VALUES (?::bigint, ?::timestamptz, ?::date, ?, ?::boolean, '{}'::jsonb)
            """.trimIndent(),
            reservableId,
            observedAt.toString(),
            targetDate,
            if (available) "available" else "booked",
            available,
        )
    }

    @Test
    fun `GET watch heatmap returns 404 for unknown id`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val resp = client.get("/api/availability/watches/99999/heatmap")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `GET watch heatmap for reservable-scoped watch returns one row`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p1", name = "Upper Pines")
            val rid = seedReservable("100", name = "A12", loop = "Loop A")
            linkReservableToPoi(rid, poiId)

            val createBody =
                """
                {"reservable_rid": "site:recgov:100", "start_date": "2026-07-04", "end_date": "2026-07-06", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            val watchId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
            insertSnapshot(rid, "2026-07-04", now.minusMinutes(1), available = true)

            val resp = client.get("/api/availability/watches/$watchId/heatmap")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(
                listOf("2026-07-04", "2026-07-05"),
                body["dates"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            val groups = body["groups"]!!.jsonArray
            assertEquals(1, groups.size)
            assertEquals("Loop A", groups[0].jsonObject["loop"]!!.jsonPrimitive.content)
            val rows = groups[0].jsonObject["rows"]!!.jsonArray
            assertEquals(1, rows.size)
            val cells = rows[0].jsonObject["cells"]!!.jsonArray
            assertEquals(2, cells.size)
            assertEquals("available", cells[0].jsonObject["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET watch heatmap for poi-scoped watch filters by loop`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        ca.floo.roadtrip.service.availability.AvailabilityWatchService(
                            ctx,
                            ca.floo.roadtrip.repo
                                .ReservableRepo(ctx),
                        ),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p2", name = "Tunnel Mountain")
            val rA1 = seedReservable("201", name = "A12", loop = "Loop A")
            val rA2 = seedReservable("202", name = "A13", loop = "Loop A")
            val rB1 = seedReservable("203", name = "B05", loop = "Loop B")
            linkReservableToPoi(rA1, poiId)
            linkReservableToPoi(rA2, poiId)
            linkReservableToPoi(rB1, poiId)

            val createBody =
                """
                {"poi_id": $poiId, "reservable_filters": {"loop": ["Loop A"]}, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            val watchId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp = client.get("/api/availability/watches/$watchId/heatmap")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val groups = body["groups"]!!.jsonArray
            assertEquals(1, groups.size)
            assertEquals("Loop A", groups[0].jsonObject["loop"]!!.jsonPrimitive.content)
            val rows = groups[0].jsonObject["rows"]!!.jsonArray
            assertEquals(2, rows.size)
            val ridsInResponse = rows.map { it.jsonObject["reservable_rid"]!!.jsonPrimitive.content }
            assertEquals(true, ridsInResponse.contains("site:recgov:201"))
            assertEquals(true, ridsInResponse.contains("site:recgov:202"))
            assertEquals(false, ridsInResponse.contains("site:recgov:203"))
        }
}
