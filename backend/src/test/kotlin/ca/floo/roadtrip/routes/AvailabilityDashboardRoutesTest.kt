package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.availability.WatchStatus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AvailabilityDashboardRoutesTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_snapshot")
        ctx.execute("DELETE FROM availability_job_run")
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

    private fun seedJob(poiId: Long): Long {
        val watchId =
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
        return AvailabilityJobRepo(ctx)
            .upsertForWatch(
                watchId = watchId,
                intentPayload = buildJsonObject { put("kind", JsonPrimitive("reservable")) },
                cadenceSec = 60,
                status = WatchStatus.ACTIVE,
                nextRunAt = OffsetDateTime.now(ZoneOffset.UTC),
            ).id
    }

    private fun seedReservable(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name
                ) VALUES (
                    'site', 'recgov', '330257', 'federal-campsites', 'A12'
                ) RETURNING id
                """.trimIndent(),
            )!!
            .get("id", Long::class.java)

    private fun insertSnapshot(
        reservableId: Long,
        targetDate: String,
        observedAt: OffsetDateTime,
        available: Boolean,
    ) {
        ctx.execute(
            """
            INSERT INTO availability_snapshot (
                reservable_id, observed_at, target_date, status, available, day_payload
            ) VALUES (?, ?::timestamptz, ?::date, ?::availability_status, ?, '{}'::jsonb)
            """.trimIndent(),
            reservableId,
            observedAt.toString(),
            targetDate,
            if (available) "available" else "reserved",
            available,
        )
    }

    @Test
    fun `GET jobs returns the seeded job`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            seedJob(seedPoi())
            val resp = client.get("/api/availability/jobs")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(1, body["total"]!!.jsonPrimitive.int)
            assertEquals(1, body["jobs"]!!.jsonArray.size)
            assertEquals(
                "active",
                body["jobs"]!!
                    .jsonArray[0]
                    .jsonObject["status"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `GET jobs summary counts by status`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            seedJob(seedPoi())
            val resp = client.get("/api/availability/jobs/summary")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(1, body["active"]!!.jsonPrimitive.int)
            assertEquals(0, body["paused"]!!.jsonPrimitive.int)
            assertEquals(0, body["done"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET runs lists runs newest first`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            val jobId = seedJob(seedPoi())
            val runRepo = AvailabilityJobRunRepo(ctx)
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val older = runRepo.start(jobId, now.minusMinutes(5))
            runRepo.complete(older, snapshotCount = 1, completedAt = now.minusMinutes(4), durationMs = 100)
            val newer = runRepo.start(jobId, now.minusMinutes(1))
            runRepo.complete(newer, snapshotCount = 2, completedAt = now, durationMs = 100)
            val resp = client.get("/api/availability/runs")
            assertEquals(HttpStatusCode.OK, resp.status)
            val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["runs"]!!.jsonArray
            assertEquals(2, rows.size)
            assertEquals(newer, rows[0].jsonObject["id"]!!.jsonPrimitive.long)
            assertEquals(older, rows[1].jsonObject["id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `GET runs filters by job_id`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            val poiId = seedPoi()
            val jobA = seedJob(poiId)
            val jobB =
                AvailabilityJobRepo(ctx)
                    .upsertForWatch(
                        watchId =
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
                                .get("id", Long::class.java),
                        intentPayload = buildJsonObject { put("kind", JsonPrimitive("reservable")) },
                        cadenceSec = 60,
                        status = WatchStatus.ACTIVE,
                        nextRunAt = OffsetDateTime.now(ZoneOffset.UTC),
                    ).id
            val runRepo = AvailabilityJobRunRepo(ctx)
            runRepo.start(jobA, OffsetDateTime.now(ZoneOffset.UTC))
            runRepo.start(jobB, OffsetDateTime.now(ZoneOffset.UTC))
            val resp = client.get("/api/availability/runs?job_id=$jobA")
            val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["runs"]!!.jsonArray
            assertEquals(1, rows.size)
            assertEquals(jobA, rows[0].jsonObject["job_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `GET snapshots requires exactly one filter`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            val resp = client.get("/api/availability/snapshots")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_filter", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET jobs id runs returns 400 on invalid id`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            val resp = client.get("/api/availability/jobs/not-a-number/runs")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `GET snapshots summary returns stats per date`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            val reservableId = seedReservable()
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            insertSnapshot(reservableId, "2026-07-04", now.minusMinutes(3), available = false)
            insertSnapshot(reservableId, "2026-07-04", now.minusMinutes(2), available = true)
            insertSnapshot(reservableId, "2026-07-04", now.minusMinutes(1), available = true)
            val resp = client.get("/api/availability/snapshots/summary?reservable_rid=site:recgov:330257")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("site:recgov:330257", body["reservable_rid"]!!.jsonPrimitive.content)
            val stats = body["stats"]!!.jsonArray
            assertEquals(1, stats.size)
            val row = stats[0].jsonObject
            assertEquals("2026-07-04", row["target_date"]!!.jsonPrimitive.content)
            assertEquals(3, row["total_snapshots"]!!.jsonPrimitive.int)
            assertEquals(true, row["is_currently_open"]!!.jsonPrimitive.boolean)
            assertEquals(1, row["flips_last_24h"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET snapshots summary requires rid`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            val resp = client.get("/api/availability/snapshots/summary")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("missing_reservable_rid", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET snapshots summary returns 404 on unknown rid`() =
        testApplication {
            application { routing { availabilityDashboardRoutes(ctx) } }
            val resp = client.get("/api/availability/snapshots/summary?reservable_rid=site:recgov:999999")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }
}
