package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.AvailabilityDashboardController
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import ca.floo.roadtrip.route.api.availability.availabilityDashboardRoutes as installAvailabilityDashboardRoutes

class AvailabilityDashboardRoutesTest : SharedDbTest() {
    private var userSeq = 0

    private fun Route.testAvailabilityDashboardRoutes() {
        installAvailabilityDashboardRoutes(
            AvailabilityDashboardController(
                pollerRepo = AvailabilityPollerRepo(ctx),
                runRepo = AvailabilityRunRepo(ctx),
                availabilityRepo = AvailabilityRepo(ctx),
                campsiteRepo = CampsiteRepo(ctx),
                forcePullCooldown = Duration.ofSeconds(60),
            ),
        )
    }

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedOwner(): Long = ca.floo.roadtrip.repo.UserRepo(ctx).create(
        email = "owner-${userSeq++}@example.com",
        displayName = null,
        isEmailVerified = true,
    ).id.value

    private fun seedPoi(sourceId: String = "p1"): Long =
        ctx
            .seedCatalogPoi(
                sourceId = sourceId,
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
            ).poiId

    /** Seeds an active poller for (recgov, [parentRef]) with one attached watch. */
    private fun seedPoller(parentRef: String = "232447"): Long {
        val poiId = seedPoi("poi-$parentRef")
        val ownerId = seedOwner()
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        owner_user_id, start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?, '2026-07-04'::date, '2026-07-05'::date, 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    ownerId,
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        val pollers = AvailabilityPollerRepo(ctx)
        val pollerId =
            pollers.upsertActive(provider = "recgov", parentRef = parentRef, poiId = poiId, pullNextRunAt = null)
        pollers.linkWatch(watchId, pollerId)
        return pollerId
    }

    private data class SummaryFixture(
        val poiId: Long,
        val campsiteId: Long,
    )

    private fun seedSummaryFixture(): SummaryFixture {
        val poi =
            ctx.seedCatalogPoi(
                sourceId = "dashboard-cg",
                name = "Dashboard Campground",
                lon = -119.56,
                lat = 37.74,
            )
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = poi.catalogId,
                vendor = "recgov",
                vendorId = "330257",
                name = "A12",
            )
        return SummaryFixture(poiId = poi.poiId, campsiteId = campsiteId)
    }

    /** Records one observation into the interval table (bump-or-insert), so the
     *  summary is derived from real status-runs. */
    private fun recordObservation(
        campsiteId: Long,
        targetDate: String,
        observedAt: OffsetDateTime,
        available: Boolean,
    ) {
        AvailabilityRepo(ctx).recordObservations(
            runId = null,
            listOf(
                AvailabilityRepo.Observation(
                    campsiteId = campsiteId,
                    targetDate = LocalDate.parse(targetDate),
                    status = if (available) AvailabilityStatus.AVAILABLE else AvailabilityStatus.RESERVED,
                    observedAt = observedAt.toInstant(),
                ),
            ),
        )
    }

    @Test
    fun `GET pollers returns the seeded poller with attached-watch count`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            seedPoller()
            val resp = client.get("/api/availability/pollers")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(1, body["total"]!!.jsonPrimitive.int)
            val row = body["pollers"]!!.jsonArray[0].jsonObject
            assertEquals(true, row["active"]!!.jsonPrimitive.boolean)
            assertEquals("recgov", row["provider"]!!.jsonPrimitive.content)
            assertEquals("232447", row["parent_ref"]!!.jsonPrimitive.content)
            assertEquals(1, row["attached_watches"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET pollers summary counts active and dormant`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            seedPoller()
            val resp = client.get("/api/availability/pollers/summary")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(1, body["active"]!!.jsonPrimitive.int)
            assertEquals(0, body["dormant"]!!.jsonPrimitive.int)
        }

    @Test
    fun `GET runs lists runs newest first`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val pollerId = seedPoller()
            val runRepo = AvailabilityRunRepo(ctx)
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val older = runRepo.start(pollerId, now.minusMinutes(5))
            runRepo.complete(older, snapshotCount = 1, completedAt = now.minusMinutes(4), durationMs = 100)
            val newer = runRepo.start(pollerId, now.minusMinutes(1))
            runRepo.complete(newer, snapshotCount = 2, completedAt = now, durationMs = 100)
            val resp = client.get("/api/availability/runs")
            assertEquals(HttpStatusCode.OK, resp.status)
            val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["runs"]!!.jsonArray
            assertEquals(2, rows.size)
            assertEquals(newer, rows[0].jsonObject["id"]!!.jsonPrimitive.long)
            assertEquals(older, rows[1].jsonObject["id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `GET runs filters by poller_id`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val pollerA = seedPoller("232447")
            val pollerB = seedPoller("232448")
            val runRepo = AvailabilityRunRepo(ctx)
            runRepo.start(pollerA, OffsetDateTime.now(ZoneOffset.UTC))
            runRepo.start(pollerB, OffsetDateTime.now(ZoneOffset.UTC))
            val resp = client.get("/api/availability/runs?poller_id=$pollerA")
            val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["runs"]!!.jsonArray
            assertEquals(1, rows.size)
            assertEquals(pollerA, rows[0].jsonObject["poller_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `POST pollers id force returns 200 with new next_run_at when outside cooldown`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val pollerId = seedPoller()
            val before = OffsetDateTime.now(ZoneOffset.UTC)
            val resp = client.post("/api/availability/pollers/$pollerId/force")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(pollerId, body["poller_id"]!!.jsonPrimitive.long)
            val nextRunAt = OffsetDateTime.parse(body["next_run_at"]!!.jsonPrimitive.content)
            // next_run_at was pulled to ~now (server-side), not left far in the future.
            assertEquals(true, !nextRunAt.isBefore(before.minusMinutes(1)))
            assertEquals(true, !nextRunAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)))
        }

    @Test
    fun `POST pollers id force returns 429 with retry_after_sec when inside cooldown`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val pollerId = seedPoller()
            val first = client.post("/api/availability/pollers/$pollerId/force")
            assertEquals(HttpStatusCode.OK, first.status)
            val second = client.post("/api/availability/pollers/$pollerId/force")
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
            val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals(pollerId, body["poller_id"]!!.jsonPrimitive.long)
            assertEquals(true, body["retry_after_sec"]!!.jsonPrimitive.long > 0)
        }

    @Test
    fun `POST pollers id force returns 404 on unknown poller`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val resp = client.post("/api/availability/pollers/999999/force")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `POST pollers id force returns 400 on invalid id`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val resp = client.post("/api/availability/pollers/not-a-number/force")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `GET changes requires exactly one filter`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val resp = client.get("/api/availability/changes")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_filter", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET pollers id runs returns 400 on invalid id`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val resp = client.get("/api/availability/pollers/not-a-number/runs")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `GET changes summary returns stats per date`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val fixture = seedSummaryFixture()
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            // reserved → available → available. The two availables bump the same
            // status-run in place, so this collapses to two runs (reserved, available).
            recordObservation(fixture.campsiteId, "2026-07-04", now.minusMinutes(3), available = false)
            recordObservation(fixture.campsiteId, "2026-07-04", now.minusMinutes(2), available = true)
            recordObservation(fixture.campsiteId, "2026-07-04", now.minusMinutes(1), available = true)
            val pollerId =
                AvailabilityPollerRepo(ctx)
                    .upsertActive(provider = "recgov", parentRef = "dashboard-cg", poiId = fixture.poiId, pullNextRunAt = null)
            val runRepo = AvailabilityRunRepo(ctx)
            val olderRun = runRepo.start(pollerId, now.minusMinutes(3))
            runRepo.complete(olderRun, snapshotCount = 1, completedAt = now.minusMinutes(3), durationMs = 10)
            val newerRun = runRepo.start(pollerId, now.minusMinutes(1))
            runRepo.complete(newerRun, snapshotCount = 1, completedAt = now.minusMinutes(1), durationMs = 10)
            val resp = client.get("/api/availability/changes/summary?poi_id=${fixture.poiId}")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(fixture.poiId, body["poi_id"]!!.jsonPrimitive.long)
            val stats = body["stats"]!!.jsonArray
            assertEquals(1, stats.size)
            val row = stats[0].jsonObject
            assertEquals("2026-07-04", row["target_date"]!!.jsonPrimitive.content)
            assertEquals(2, row["total_runs"]!!.jsonPrimitive.int)
            assertEquals(true, row["is_currently_open"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `GET changes summary requires poi id`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val resp = client.get("/api/availability/changes/summary")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("missing_poi_id", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET changes summary returns 404 on unknown poi id`() =
        testApplication {
            application { routing { testAvailabilityDashboardRoutes() } }
            val resp = client.get("/api/availability/changes/summary?poi_id=999999")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }
}
