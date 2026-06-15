package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityPollerRepo
import ca.floo.roadtrip.repo.ReservableAvailabilityRunRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.service.api.AvailabilityCacheBlock
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.DayClassification
import ca.floo.roadtrip.service.api.ReservableAvailabilityIntentService
import ca.floo.roadtrip.service.api.ReservableAvailabilityPollerService
import ca.floo.roadtrip.service.api.availabilityResponseDto
import ca.floo.roadtrip.service.booking.AvailabilityRequest
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingCapabilities
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderId
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservableRoutesTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun start() {
        System.setProperty("api.version", "1.44")
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
    fun reset() {
        ctx.execute("DELETE FROM reservable_availability_log")
        ctx.execute("DELETE FROM reservable_availability_runs")
        ctx.execute("DELETE FROM reservable_availability_pollers")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
        ctx.execute("DELETE FROM import_runs")
    }

    @Test
    fun `reservable detail returns catalog fields and linked poi ids`() =
        testApplication {
            val poiId = seedPoi("upper-pines", "Upper Pines Campground")
            val reservableId =
                seedReservable(
                    vendorId = "330257",
                    name = "A12",
                    loop = "Loop A",
                    siteType = "STANDARD",
                    raw = """{"campsite_id":"330257","reservable":true}""",
                )
            link(reservableId, poiId)
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/reservable/site:recgov:330257")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val reservable = body["reservable"]!!.jsonObject
            assertEquals("site:recgov:330257", reservable["rid"]!!.jsonPrimitive.content)
            assertEquals("site", reservable["type"]!!.jsonPrimitive.content)
            assertEquals("recgov", reservable["vendor"]!!.jsonPrimitive.content)
            assertEquals("330257", reservable["vendor_id"]!!.jsonPrimitive.content)
            assertEquals("A12", reservable["name"]!!.jsonPrimitive.content)
            assertEquals("Loop A", reservable["loop"]!!.jsonPrimitive.content)
            assertEquals("STANDARD", reservable["site_type"]!!.jsonPrimitive.content)
            assertEquals("330257", reservable["raw"]!!.jsonObject["campsite_id"]!!.jsonPrimitive.content)
            assertEquals(listOf(poiId.toString()), body["poi_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(poiId.toString()), reservable["poi_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `reservable detail returns 404 for unknown rid`() =
        testApplication {
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/reservable/site:recgov:missing")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("not_found", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `reservable detail returns 400 for malformed rid`() =
        testApplication {
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/reservable/not-a-rid")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_rid", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `reservables search ORs within fields and ANDs across fields`() =
        testApplication {
            val poiId = seedPoi("upper-pines", "Upper Pines Campground")
            val linkedReservable = seedReservable(vendorId = "330257", name = "A12", loop = "Loop A")
            seedReservable(vendorId = "330258", name = "B03", loop = "Loop B")
            seedReservable(
                vendor = "aspira_pc",
                vendorId = "-2147483641",
                source = "aspira-resources-pc",
                name = "A12",
                loop = "Loop A",
                raw = """{"host":"reservation.pc.gc.ca","map_id":101}""",
            )
            link(linkedReservable, poiId)
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/reservables?type=site&vendor=recgov&vendor_id=330257,330258")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("2", body["total"]!!.jsonPrimitive.content)
            assertEquals("100", body["limit"]!!.jsonPrimitive.content)
            assertEquals("0", body["offset"]!!.jsonPrimitive.content)
            val rids =
                body["reservables"]!!
                    .jsonArray
                    .map { it.jsonObject["rid"]!!.jsonPrimitive.content }
                    .toSet()
            assertEquals(setOf("site:recgov:330257", "site:recgov:330258"), rids)
            val linkedRow =
                body["reservables"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it["rid"]!!.jsonPrimitive.content == "site:recgov:330257" }
            assertEquals(listOf(poiId.toString()), linkedRow["poi_ids"]!!.jsonArray.map { it.jsonPrimitive.content })

            val paged = client.get("/api/reservables?vendor=recgov,aspira_pc&name=A12&limit=1&offset=1")
            assertEquals(HttpStatusCode.OK, paged.status)
            val pagedBody = Json.parseToJsonElement(paged.bodyAsText()).jsonObject
            assertEquals("2", pagedBody["total"]!!.jsonPrimitive.content)
            assertEquals("1", pagedBody["limit"]!!.jsonPrimitive.content)
            assertEquals("1", pagedBody["offset"]!!.jsonPrimitive.content)
            assertEquals(1, pagedBody["reservables"]!!.jsonArray.size)

            val raw = client.get("/api/reservables?raw=%7B%22host%22%3A%22reservation.pc.gc.ca%22%7D")
            assertEquals(HttpStatusCode.OK, raw.status)
            val rawBody = Json.parseToJsonElement(raw.bodyAsText()).jsonObject
            assertEquals("1", rawBody["total"]!!.jsonPrimitive.content)
            val rawRid =
                rawBody["reservables"]!!
                    .jsonArray
                    .single()
                    .jsonObject["rid"]!!
                    .jsonPrimitive
                    .content
            assertEquals(
                "site:aspira_pc:-2147483641",
                rawRid,
            )
        }

    @Test
    fun `reservables search rejects bad filters`() =
        testApplication {
            application { routing { reservableRoutes(ctx) } }

            assertEquals(HttpStatusCode.BadRequest, client.get("/api/reservables?type=permit").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/reservables?limit=0").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/reservables?raw=%7Bnot-json%7D").status)
        }

    @Test
    fun `reservable availability poller create and list`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val reservableId = seedReservable(vendorId = "330257", name = "A12", loop = "Loop A")
            link(reservableId, poiId)
            application {
                routing {
                    reservableRoutes(
                        ctx,
                        fakeBookingProviders(),
                        CampsiteProviderRepo(ctx),
                        ReservableAvailabilityLogRepo(ctx),
                    )
                }
            }

            val created =
                client.post("/api/reservable/site:recgov:330257/availability/poller") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "target_dates":["2026-07-01"],
                          "min_nights":1,
                          "cadence":60,
                          "trigger_actions":["notify_slack"],
                          "stop_when_triggered":false
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)
            val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val poller = createdBody["poller"]!!.jsonObject
            val pollerId = poller["id"]!!.jsonPrimitive.content
            assertEquals("60", poller["cadence"]!!.jsonPrimitive.content)
            assertEquals(listOf("notify_slack"), poller["trigger_actions"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(false, poller["stop_when_triggered"]!!.jsonPrimitive.boolean)
            assertEquals("active", poller["status"]!!.jsonPrimitive.content)
            assertEquals("site:recgov:330257", poller["scope"]!!.jsonObject["rid"]!!.jsonPrimitive.content)
            assertEquals(listOf("2026-07-01"), poller["target_dates"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("completed", createdBody["initial_run"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals("1", createdBody["initial_run"]!!.jsonObject["log_count"]!!.jsonPrimitive.content)

            val runs = client.get("/api/reservables/availability/runs?poller_id=$pollerId")
            assertEquals(HttpStatusCode.OK, runs.status)
            val runsBody = Json.parseToJsonElement(runs.bodyAsText()).jsonObject
            assertEquals(1, runsBody["runs"]!!.jsonArray.size)

            val list = client.get("/api/reservables/availability/pollers")
            assertEquals(HttpStatusCode.OK, list.status)
            val listBody = Json.parseToJsonElement(list.bodyAsText()).jsonObject
            assertEquals(1, listBody["pollers"]!!.jsonArray.size)
            assertEquals(
                listOf("notify_slack"),
                listBody["pollers"]!!
                    .jsonArray
                    .single()
                    .jsonObject["trigger_actions"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun `reservable availability poller rejects invalid input`() =
        testApplication {
            seedReservable(vendorId = "330257", name = "A12")
            application {
                routing {
                    reservableRoutes(
                        ctx,
                        fakeBookingProviders(),
                        CampsiteProviderRepo(ctx),
                        ReservableAvailabilityLogRepo(ctx),
                    )
                }
            }

            val badCadence =
                client.post("/api/reservable/site:recgov:330257/availability/poller") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_dates":["2026-07-01"],"cadence":1,"trigger_actions":["notify_slack"]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badCadence.status)

            val emptyAction =
                client.post("/api/reservable/site:recgov:330257/availability/poller") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_dates":["2026-07-01"],"cadence":60,"trigger_actions":[]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, emptyAction.status)

            val unknown =
                client.post("/api/reservable/site:recgov:missing/availability/poller") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_dates":["2026-07-01"],"cadence":60,"trigger_actions":["notify_slack"]}""")
                }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
        }

    @Test
    fun `reservable availability intent query writes runs and log rows`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val standard = seedReservable(vendorId = "330257", name = "A12", siteType = "STANDARD")
            val tent = seedReservable(vendorId = "330258", name = "B03", siteType = "TENT")
            link(standard, poiId)
            link(tent, poiId)
            application {
                routing {
                    reservableRoutes(
                        ctx,
                        fakeBookingProviders(),
                        CampsiteProviderRepo(ctx),
                        ReservableAvailabilityLogRepo(ctx),
                    )
                }
            }

            val resp =
                client.post("/api/reservables/availability/query") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "scope":{"poi_id":$poiId},
                          "reservable_filters":{"site_type":["STANDARD"]},
                          "start_date":"2026-07-01",
                          "days":2,
                          "min_nights":1
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("1", body["candidate_count"]!!.jsonPrimitive.content)
            assertEquals("2", body["log_count"]!!.jsonPrimitive.content)
            assertEquals(1, body["results"]!!.jsonArray.size)
            assertEquals(
                listOf("2026-07-01", "2026-07-02"),
                body["results"]!!
                    .jsonArray
                    .single()
                    .jsonObject["matching_starts"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )

            val runId = body["run_id"]!!.jsonPrimitive.content
            val logs = client.get("/api/reservables/availability/logs?run_id=$runId")
            assertEquals(HttpStatusCode.OK, logs.status)
            val logsBody = Json.parseToJsonElement(logs.bodyAsText()).jsonObject
            assertEquals(2, logsBody["logs"]!!.jsonArray.size)
            assertEquals(
                setOf("2026-07-01", "2026-07-02"),
                logsBody["logs"]!!
                    .jsonArray
                    .map { it.jsonObject["target_date"]!!.jsonPrimitive.content }
                    .toSet(),
            )
        }

    @Test
    fun `availability poller service claims due pollers and appends log rows`() =
        runBlocking {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val reservableId = seedReservable(vendorId = "330257", name = "A12", siteType = "STANDARD")
            link(reservableId, poiId)

            val pollers = ReservableAvailabilityPollerRepo(ctx)
            val logs = ReservableAvailabilityLogRepo(ctx)
            val runs = ReservableAvailabilityRunRepo(ctx)
            val poller =
                pollers.create(
                    ReservableAvailabilityPollerRepo.CreateInput(
                        scope =
                            ReservableAvailabilityPollerRepo.Scope(
                                reservableId = reservableId,
                                reservableRid = ReservableId.parse("site:recgov:330257"),
                            ),
                        reservableFilters = buildJsonObject {},
                        targetDates = listOf(LocalDate.parse("2026-07-01")),
                        minNights = 1,
                        cadenceSec = 60,
                        triggerActions = JsonArray(listOf(JsonPrimitive("notify_slack"))),
                        stopWhenTriggered = true,
                    ),
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val service =
                    ReservableAvailabilityPollerService(
                        pollers = pollers,
                        intents =
                            ReservableAvailabilityIntentService(
                                providerRefs = CampsiteProviderRepo(ctx),
                                bookingProviders = fakeBookingProviders(),
                                reservables = ReservableRepo(ctx),
                                pois = PoiServingRepo(ctx),
                                availabilityLogs = logs,
                                runs = runs,
                            ),
                        scope = scope,
                    )

                assertEquals(1, service.pollDueOnce(limit = 1))

                val updated = pollers.get(poller.id)!!
                assertEquals("done", updated.status)
                assertNotNull(updated.lastCheckedAt)
                assertNotNull(updated.lastTriggeredAt)

                val rows =
                    logs.list(
                        ReservableAvailabilityLogRepo.LogFilters(
                            pollerId = poller.id,
                            targetDate = LocalDate.parse("2026-07-01"),
                        ),
                    )
                assertEquals(1, rows.size)
                assertEquals("site:recgov:330257", rows.single().reservableRid)
                assertEquals(true, rows.single().available)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `poi reservables lists linked site reservables and total count`() =
        testApplication {
            val poiId = seedPoi("upper-pines", "Upper Pines Campground")
            val otherPoiId = seedPoi("mather", "Mather Campground")
            val a12 = seedReservable(vendorId = "330257", name = "A12", loop = "Loop A")
            val b03 = seedReservable(vendorId = "330258", name = "B03", loop = "Loop B")
            val m01 = seedReservable(vendorId = "330999", name = "M01", loop = "Loop M")
            link(a12, poiId)
            link(b03, poiId)
            link(m01, otherPoiId)
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/$poiId/reservables?type=site")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(poiId.toString(), body["poi_id"]!!.jsonPrimitive.content)
            assertEquals("site", body["type"]!!.jsonPrimitive.content)
            assertEquals("2", body["total_at_poi"]!!.jsonPrimitive.content)
            val rids =
                body["reservables"]!!
                    .jsonArray
                    .map { it.jsonObject["rid"]!!.jsonPrimitive.content }
                    .toSet()
            assertEquals(setOf("site:recgov:330257", "site:recgov:330258"), rids)
            body["reservables"]!!
                .jsonArray
                .map { it.jsonObject }
                .forEach { row ->
                    assertEquals(listOf(poiId.toString()), row["poi_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
                }
        }

    @Test
    fun `poi reservables defaults type to site`() =
        testApplication {
            val poiId = seedPoi("upper-pines", "Upper Pines Campground")
            val site = seedReservable(vendorId = "330257", name = "A12")
            link(site, poiId)
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/$poiId/reservables")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("site", body["type"]!!.jsonPrimitive.content)
            assertEquals(1, body["reservables"]!!.jsonArray.size)
        }

    @Test
    fun `poi reservables returns empty list for active poi with no reservables`() =
        testApplication {
            val poiId = seedPoi("empty", "Empty Campground")
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/$poiId/reservables?type=site")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("0", body["total_at_poi"]!!.jsonPrimitive.content)
            assertEquals(0, body["reservables"]!!.jsonArray.size)
        }

    @Test
    fun `poi reservables rejects malformed id and unknown type`() =
        testApplication {
            val poiId = seedPoi("upper-pines", "Upper Pines Campground")
            application { routing { reservableRoutes(ctx) } }

            assertEquals(HttpStatusCode.BadRequest, client.get("/api/poi/nope/reservables").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/poi/$poiId/reservables?type=permit").status)
        }

    @Test
    fun `poi reservables returns 404 for unknown poi`() =
        testApplication {
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/999999/reservables?type=site")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `poi availability alias dispatches through booking provider`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            application {
                routing {
                    campsiteAvailabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeBookingProviders(),
                        ReservableRepo(ctx),
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/availability?start=2026-07-01&days=1")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("fake", body["provider"]!!.jsonPrimitive.content)
            assertEquals("232447", body["campground_id"]!!.jsonPrimitive.content)
            assertEquals("2026-07-01", body["window"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        }

    @Test
    fun `reservable availability dispatches by linked campground provider`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val reservableId = seedReservable(vendorId = "330257", name = "A12")
            link(reservableId, poiId)
            application {
                routing {
                    campsiteAvailabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeBookingProviders(),
                        ReservableRepo(ctx),
                        ReservableAvailabilityLogRepo(ctx),
                    )
                }
            }

            val resp = client.get("/api/reservable/site:recgov:330257/availability?start=2026-07-01&days=2")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("fake", body["provider"]!!.jsonPrimitive.content)
            assertEquals("site:recgov:330257", body["reservable_id"]!!.jsonPrimitive.content)
            assertEquals(2, body["availability"]!!.jsonArray.size)
            val firstDate =
                body["availability"]!!
                    .jsonArray
                    .first()
                    .jsonObject["date"]!!
                    .jsonPrimitive
                    .content
            assertEquals("2026-07-01", firstDate)

            val logRows =
                ctx.fetch(
                    """
                    SELECT reservable_rid, target_date, status, available, day_payload
                    FROM reservable_availability_log
                    ORDER BY target_date
                    """.trimIndent(),
                )
            assertEquals(2, logRows.size)
            assertEquals("site:recgov:330257", logRows[0].get("reservable_rid", String::class.java))
            assertEquals(java.time.LocalDate.parse("2026-07-01"), logRows[0].get("target_date", java.time.LocalDate::class.java))
            assertEquals("available", logRows[0].get("status", String::class.java))
            assertEquals(true, logRows[0].get("available", Boolean::class.java))
            assertEquals(
                "2026-07-01",
                Json
                    .parseToJsonElement(logRows[0].get("day_payload").toString())
                    .jsonObject["date"]!!
                    .jsonPrimitive
                    .content,
            )

            val multiNight = client.get("/api/reservable/site:recgov:330257/availability?start=2026-07-01&days=2&min_nights=2")
            assertEquals(HttpStatusCode.OK, multiNight.status)
            val rowCountAfterMultiNight =
                ctx
                    .fetchOne("SELECT count(*) FROM reservable_availability_log")!!
                    .get(0, Long::class.java)
            assertEquals(5L, rowCountAfterMultiNight)
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
        type: String = "site",
        vendor: String = "recgov",
        vendorId: String,
        source: String = "federal-campsites",
        name: String,
        loop: String? = null,
        siteType: String? = null,
        raw: String = """{"source":"test"}""",
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name, loop, site_type, raw
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                type,
                vendor,
                vendorId,
                source,
                name,
                loop,
                siteType,
                raw,
            )!!
            .get("id", Long::class.java)

    private fun link(
        reservableId: Long,
        poiId: Long,
    ) {
        ctx.execute(
            """
            INSERT INTO reservable_pois (reservable_id, poi_id)
            VALUES (?, ?)
            """.trimIndent(),
            reservableId,
            poiId,
        )
    }

    private fun fakeBookingProviders(): BookingProviderRegistry =
        BookingProviderRegistry(
            adapters = mapOf(BookingProviderId.RECGOV to FakeBookingProvider),
            sourceToProviderId = mapOf("test" to BookingProviderId.RECGOV),
        )

    private object FakeBookingProvider : BookingProvider {
        override val id: BookingProviderId = BookingProviderId.RECGOV
        override val capabilities: BookingCapabilities =
            BookingCapabilities(
                supportsAvailability = true,
                supportsAlerts = false,
                supportsAutoBook = false,
                bookingHorizonDays = 365,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
            val ref = req.ref as ProviderRef.RecGov
            return fakeResponse(
                start = req.start,
                days = req.days,
                campgroundId = ref.recgovId,
                reservableId = null,
            )
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityResponseDto =
            fakeResponse(
                start = req.start,
                days = req.days,
                campgroundId = null,
                reservableId = "site:recgov:${req.vendorId}",
            )

        override suspend fun availableDates(req: AvailableDatesRequest): List<String> = listOf(req.start.toString())

        private fun fakeResponse(
            start: java.time.LocalDate,
            days: Int,
            campgroundId: String?,
            reservableId: String?,
        ): AvailabilityResponseDto {
            val perDay =
                (0 until days).map { offset ->
                    DayClassification(
                        date = start.plusDays(offset.toLong()).toString(),
                        status = "available",
                        availableCount = 1,
                        total = 1,
                    )
                }
            return availabilityResponseDto(
                provider = "fake",
                today = start,
                days = days,
                perDay = perDay,
                state = "success",
                summary = "$days nights available",
                seasonBlock = null,
                cacheBlock = AvailabilityCacheBlock(hit = true, ageSeconds = 0, ttlSeconds = 60),
                campgroundId = campgroundId,
                reservableId = reservableId,
            )
        }
    }
}
