package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.service.api.AvailabilityCacheBlock
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.DayClassification
import ca.floo.roadtrip.service.api.availabilityResponseDto
import ca.floo.roadtrip.service.booking.AvailabilityRequest
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingCapabilities
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderId
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.CatalogAvailabilityRequest
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import org.junit.jupiter.api.assertAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        ctx.execute("DELETE FROM availability_snapshot")
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
                source = "aspira-pc-resources",
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

            val resp = client.get("/api/poi/$poiId/reservables?type=site&start=2026-07-01&min_nights=2")
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
            val urls =
                body["reservables"]!!
                    .jsonArray
                    .associate {
                        val row = it.jsonObject
                        row["rid"]!!.jsonPrimitive.content to row["reservation_url"]!!.jsonPrimitive.content
                    }
            assertEquals(
                "https://www.recreation.gov/camping/campsites/330257?startDate=2026-07-01&endDate=2026-07-03",
                urls["site:recgov:330257"],
            )
            body["reservables"]!!
                .jsonArray
                .map { it.jsonObject }
                .forEach { row ->
                    assertEquals(listOf(poiId.toString()), row["poi_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
                }
        }

    @Test
    fun `poi reservables returns aspira booking links from parent provider ref`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "aspira--2147483630--2147483388",
                    name = "Deep Tree Park",
                    providerRefJson =
                        """
                        {
                          "transactionLocationId": -2147483630,
                          "mapId": -2147483388,
                          "resourceLocationId": -2147483624
                        }
                        """.trimIndent(),
                    source = "aspira-wa-pins",
                )
            val reservableId =
                seedReservable(
                    vendor = "aspira_wa",
                    vendorId = "-100",
                    source = "aspira-resources-wa",
                    name = "A",
                    raw = """{"_parent_aspira_map_id":-2147483615,"_parent_aspira_resource_loc":-2147483624}""",
                    providerRefJson = """{"mapId":-2147483615,"resourceLocationId":-2147483624}""",
                )
            link(reservableId, poiId)
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/$poiId/reservables?start=2026-07-01&min_nights=2")
            assertEquals(HttpStatusCode.OK, resp.status)
            val row =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["reservables"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            val url = row["reservation_url"]!!.jsonPrimitive.content
            assertTrue(url.startsWith("https://washington.goingtocamp.com/create-booking/results?"), url)
            assertTrue(url.contains("transactionLocationId=-2147483630"), url)
            assertTrue(url.contains("mapId=-2147483615"), url)
            assertTrue(url.contains("startDate=2026-07-01"), url)
            assertTrue(url.contains("endDate=2026-07-03"), url)
            assertTrue(url.contains("nights=2"), url)
            assertTrue(url.contains("resourceLocationId=-2147483624"), url)
            assertTrue(!url.contains("filterData="), url)
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
    fun `poi reservables filters by site type`() =
        testApplication {
            val poiId = seedPoi("upper-pines", "Upper Pines Campground")
            val standard = seedReservable(vendorId = "330257", name = "A12", siteType = "STANDARD")
            val tent = seedReservable(vendorId = "330258", name = "B03", siteType = "TENT ONLY")
            link(standard, poiId)
            link(tent, poiId)
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/$poiId/reservables?site_type=STANDARD")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("1", body["total_at_poi"]!!.jsonPrimitive.content)
            val row = body["reservables"]!!.jsonArray.single().jsonObject
            assertEquals("site:recgov:330257", row["rid"]!!.jsonPrimitive.content)
            assertEquals("STANDARD", row["site_type"]!!.jsonPrimitive.content)
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
    fun `poi availability uses exclusive start and end date window`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines-window",
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

            val resp = client.get("/api/poi/$poiId/availability?start_date=2026-07-01&end_date=2026-07-04")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("2026-07-01", body["window"]!!.jsonObject["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-04", body["window"]!!.jsonObject["end_date"]!!.jsonPrimitive.content)
            assertEquals(3, body["availability"]!!.jsonArray.size)
        }

    @Test
    fun `availability routes reject removed days and min nights params`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines-removed-params",
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
                    )
                }
            }

            val windowQuery = "start_date=2026-07-01&end_date=2026-07-04"
            val poiDaysStatus = client.get("/api/poi/$poiId/availability?$windowQuery&days=7").status
            val poiMinNightsStatus = client.get("/api/poi/$poiId/availability?$windowQuery&min_nights=2").status
            val reservableDaysStatus = client.get("/api/reservable/site:recgov:330257/availability?$windowQuery&days=7").status
            val reservableMinNightsStatus =
                client.get("/api/reservable/site:recgov:330257/availability?$windowQuery&min_nights=2").status

            assertAll(
                { assertEquals(HttpStatusCode.BadRequest, poiDaysStatus) },
                { assertEquals(HttpStatusCode.BadRequest, poiMinNightsStatus) },
                { assertEquals(HttpStatusCode.BadRequest, reservableDaysStatus) },
                {
                    assertEquals(
                        HttpStatusCode.BadRequest,
                        reservableMinNightsStatus,
                    )
                },
            )
        }

    @Test
    fun `poi availability filters available reservable ids by site type`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val a12 = seedReservable(vendorId = "330257", name = "A12", loop = "Loop A", siteType = "STANDARD")
            val b03 = seedReservable(vendorId = "330258", name = "B03", loop = "Loop B", siteType = "TENT ONLY")
            val c44 = seedReservable(vendorId = "330259", name = "C44", loop = "Loop C", siteType = "STANDARD")
            link(a12, poiId)
            link(b03, poiId)
            link(c44, poiId)
            application {
                routing {
                    campsiteAvailabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeBookingProviders(),
                        ReservableRepo(ctx),
                    )
                }
            }

            val resp =
                client.get(
                    "/api/poi/$poiId/availability?start=2026-07-01&days=2&min_nights=2&site_type=STANDARD",
                )
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("fake", body["provider"]!!.jsonPrimitive.content)
            assertEquals("2026-07-01", body["window"]!!.jsonObject["start"]!!.jsonPrimitive.content)
            val first = body["availability"]!!.jsonArray.first().jsonObject
            assertEquals("2026-07-01", first["date"]!!.jsonPrimitive.content)
            assertEquals(2, first["available_count"]!!.jsonPrimitive.int)
            assertEquals(2, first["total"]!!.jsonPrimitive.int)
            assertEquals(
                listOf("site:recgov:330257", "site:recgov:330259"),
                first["available_reservable_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
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
                        AvailabilitySnapshotRepo(ctx),
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
                    SELECT
                        r.type || ':' || r.vendor || ':' || r.vendor_id AS reservable_rid,
                        s.target_date,
                        s.status,
                        s.available,
                        s.day_payload
                    FROM availability_snapshot s
                    JOIN reservables r ON r.id = s.reservable_id
                    ORDER BY s.target_date
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
                    .fetchOne("SELECT count(*) FROM availability_snapshot")!!
                    .get(0, Long::class.java)
            assertEquals(5L, rowCountAfterMultiNight)
        }

    @Test
    fun `reservable availability uses exclusive start and end date window`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines-reservable-window",
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
                        AvailabilitySnapshotRepo(ctx),
                    )
                }
            }

            val resp = client.get("/api/reservable/site:recgov:330257/availability?start_date=2026-07-01&end_date=2026-07-04")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("2026-07-01", body["window"]!!.jsonObject["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-04", body["window"]!!.jsonObject["end_date"]!!.jsonPrimitive.content)
            assertEquals(3, body["availability"]!!.jsonArray.size)
            assertEquals(3L, ctx.fetchOne("SELECT count(*) FROM availability_snapshot")!!.get(0, Long::class.java))
        }

    @Test
    fun `bulk availability accepts start and end date window`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "bulk-window",
                    name = "Bulk Window Campground",
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

            val resp =
                client.post("/api/campsite/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"ids":[$poiId],"start_date":"2026-07-01","end_date":"2026-07-04"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("2026-07-01", body["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-04", body["end_date"]!!.jsonPrimitive.content)
        }

    @Test
    fun `poi availability passes linked aspira child-map catalog to provider`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "aspira--2147483630--2147483388",
                    name = "Deep Tree Park",
                    providerRefJson =
                        """
                        {
                          "transactionLocationId": -2147483630,
                          "mapId": -2147483388,
                          "resourceLocationId": -2147483624
                        }
                        """.trimIndent(),
                    source = "aspira-wa-pins",
                )
            val a =
                seedReservable(
                    vendor = "aspira_wa",
                    vendorId = "-100",
                    source = "aspira-resources-wa",
                    name = "A",
                    raw = """{"_parent_aspira_map_id":-2147483615,"_parent_aspira_resource_loc":-2147483624}""",
                    providerRefJson = """{"mapId":-2147483615,"resourceLocationId":-2147483624}""",
                )
            val b =
                seedReservable(
                    vendor = "aspira_wa",
                    vendorId = "-200",
                    source = "aspira-resources-wa",
                    name = "B",
                    raw = """{"_parent_aspira_map_id":-2147483613,"_parent_aspira_resource_loc":-2147483624}""",
                    providerRefJson = """{"mapId":-2147483613,"resourceLocationId":-2147483624}""",
                )
            link(a, poiId)
            link(b, poiId)
            application {
                routing {
                    campsiteAvailabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeAspiraBookingProviders(),
                        ReservableRepo(ctx),
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/availability?start=2026-07-01&days=1")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val day = body["availability"]!!.jsonArray.single().jsonObject
            assertEquals("-2147483388", body["map_id"]!!.jsonPrimitive.content)
            assertEquals(2, day["available_count"]!!.jsonPrimitive.int)
            assertEquals(2, day["total"]!!.jsonPrimitive.int)
            assertEquals(
                listOf("site:aspira_wa:-100", "site:aspira_wa:-200"),
                day["available_reservable_ids"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun `reservable availability uses aspira reservable child map when present`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "aspira--2147483630--2147483388",
                    name = "Deep Tree Park",
                    providerRefJson =
                        """
                        {
                          "transactionLocationId": -2147483630,
                          "mapId": -2147483388,
                          "resourceLocationId": -2147483624
                        }
                        """.trimIndent(),
                    source = "aspira-wa-pins",
                )
            val reservableId =
                seedReservable(
                    vendor = "aspira_wa",
                    vendorId = "-100",
                    source = "aspira-resources-wa",
                    name = "A",
                    raw = """{"_parent_aspira_map_id":-2147483615,"_parent_aspira_resource_loc":-2147483624}""",
                    providerRefJson = """{"mapId":-2147483615,"resourceLocationId":-2147483624}""",
                )
            link(reservableId, poiId)
            application {
                routing {
                    campsiteAvailabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeAspiraBookingProviders(),
                        ReservableRepo(ctx),
                    )
                }
            }

            val resp = client.get("/api/reservable/site:aspira_wa:-100/availability?start=2026-07-01&days=1")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("-2147483615", body["map_id"]!!.jsonPrimitive.content)
            assertEquals("site:aspira_wa:-100", body["reservable_id"]!!.jsonPrimitive.content)
        }

    private fun seedPoi(
        sourceId: String,
        name: String,
        providerRefJson: String? = null,
        source: String = "test",
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom,
                    region, properties, provider_ref, fetched_at
                ) VALUES (
                    ?, ?, 'campground', ?,
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                )
                RETURNING id
                """.trimIndent(),
                source,
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
        providerRefJson: String? = null,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name, loop, site_type, raw, provider_ref
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb
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
                providerRefJson,
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
            adaptersBySource = mapOf("test" to FakeBookingProvider),
        )

    private fun fakeAspiraBookingProviders(): BookingProviderRegistry =
        BookingProviderRegistry(
            adaptersBySource = mapOf("aspira-wa-pins" to FakeAspiraBookingProvider),
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

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityResponseDto {
            val ref = req.ref as ProviderRef.RecGov
            return fakeResponse(
                start = req.start,
                days = req.days,
                campgroundId = ref.recgovId,
                reservableId = null,
                availableIds = req.reservables.map { it.rid },
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
            availableIds: List<String>? = null,
        ): AvailabilityResponseDto {
            val perDay =
                (0 until days).map { offset ->
                    val availableCount = availableIds?.size ?: 1
                    DayClassification(
                        date = start.plusDays(offset.toLong()).toString(),
                        status = if (availableIds?.isEmpty() == true) "booked" else "available",
                        availableCount = availableCount,
                        total = availableIds?.size ?: 1,
                        availableReservableIds = availableIds,
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

    private object FakeAspiraBookingProvider : BookingProvider {
        override val id: BookingProviderId = BookingProviderId.ASPIRA
        override val capabilities: BookingCapabilities =
            BookingCapabilities(
                supportsAvailability = true,
                supportsAlerts = false,
                supportsAutoBook = false,
                bookingHorizonDays = 365,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
            val ref = req.ref as ProviderRef.Aspira
            return fakeResponse(
                start = req.start,
                days = req.days,
                mapId = ref.mapId.toString(),
                reservableId = null,
                availableIds = emptyList(),
            )
        }

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityResponseDto {
            val ref = req.ref as ProviderRef.Aspira
            return fakeResponse(
                start = req.start,
                days = req.days,
                mapId = ref.mapId.toString(),
                reservableId = null,
                availableIds = req.reservables.map { it.rid },
            )
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityResponseDto {
            val ref = req.ref as ProviderRef.Aspira
            return fakeResponse(
                start = req.start,
                days = req.days,
                mapId = ref.mapId.toString(),
                reservableId = "site:aspira_wa:${req.vendorId}",
                availableIds = listOf("site:aspira_wa:${req.vendorId}"),
            )
        }

        override suspend fun availableDates(req: AvailableDatesRequest): List<String> = listOf(req.start.toString())

        private fun fakeResponse(
            start: java.time.LocalDate,
            days: Int,
            mapId: String,
            reservableId: String?,
            availableIds: List<String>,
        ): AvailabilityResponseDto {
            val perDay =
                (0 until days).map { offset ->
                    DayClassification(
                        date = start.plusDays(offset.toLong()).toString(),
                        status = if (availableIds.isEmpty()) "booked" else "available",
                        availableCount = availableIds.size,
                        total = availableIds.size,
                        availableReservableIds = availableIds,
                    )
                }
            return availabilityResponseDto(
                provider = "aspira",
                today = start,
                days = days,
                perDay = perDay,
                state = if (availableIds.isEmpty()) "zero_available" else "success",
                summary = "${availableIds.size} available",
                seasonBlock = null,
                cacheBlock = AvailabilityCacheBlock(hit = true, ageSeconds = 0, ttlSeconds = 60),
                host = "washington.goingtocamp.com",
                mapId = mapId,
                reservableId = reservableId,
            )
        }
    }
}
