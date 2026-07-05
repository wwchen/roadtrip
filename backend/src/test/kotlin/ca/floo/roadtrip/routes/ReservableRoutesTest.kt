package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.AvailabilityCacheStoreImpl
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.ReservableAvailabilityComposer
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReservableRoutesTest : SharedDbTest() {
    /**
     * Fixed "now" for tests that hardcode 2026-07-01-era availability query
     * dates. Pinning the clock well before those dates keeps the assertions
     * robust to date drift without changing production date-window logic.
     */
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun reset() {
        ctx.execute("DELETE FROM availability_snapshot")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
        ctx.execute("DELETE FROM import_runs")
        FakeReservationProvider.reset()
        FakeAspiraReservationProvider.reset()
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
                    tagsJson = """{"capacity":{"max":8},"equipment":["Tent"],"attributes":{"fire_pit":"Yes"}}""",
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
            val tags = reservable["tags"]!!.jsonObject
            assertEquals("8", tags["capacity"]!!.jsonObject["max"]!!.jsonPrimitive.content)
            val equipment =
                tags["equipment"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content
            assertEquals("Tent", equipment)
            assertEquals(
                "Yes",
                tags["attributes"]!!
                    .jsonObject["fire_pit"]!!
                    .jsonPrimitive
                    .content,
            )
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
                tagsJson = """{"equipment":["Small Tent"],"attributes":{"firepit_on_site":"Yes"}}""",
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

            val tags = client.get("/api/reservables?tags=%7B%22attributes%22%3A%7B%22firepit_on_site%22%3A%22Yes%22%7D%7D")
            assertEquals(HttpStatusCode.OK, tags.status)
            val tagsBody = Json.parseToJsonElement(tags.bodyAsText()).jsonObject
            assertEquals("1", tagsBody["total"]!!.jsonPrimitive.content)
            val tagsRid =
                tagsBody["reservables"]!!
                    .jsonArray
                    .single()
                    .jsonObject["rid"]!!
                    .jsonPrimitive
                    .content
            assertEquals("site:aspira_pc:-2147483641", tagsRid)
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
    fun `poi reservables lists linked site reservables`() =
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
            val rids =
                body["reservables"]!!
                    .jsonArray
                    .map { it.jsonObject["rid"]!!.jsonPrimitive.content }
                    .toSet()
            assertEquals(setOf("site:recgov:330257", "site:recgov:330258"), rids)
            val templates =
                body["reservables"]!!
                    .jsonArray
                    .associate {
                        val row = it.jsonObject
                        row["rid"]!!.jsonPrimitive.content to row["reservation_url_template"]!!.jsonPrimitive.content
                    }
            body["reservables"]!!
                .jsonArray
                .map { it.jsonObject }
                .forEach { row ->
                    assertTrue(
                        !row.containsKey("reservation_url"),
                        "rec.gov rows should expose reservation_url_template, not concrete reservation_url",
                    )
                }
            assertEquals(
                "https://www.recreation.gov/camping/campsites/330257?startDate={start_date}&endDate={end_date}",
                templates["site:recgov:330257"],
            )
            body["reservables"]!!
                .jsonArray
                .map { it.jsonObject }
                .forEach { row ->
                    assertEquals(listOf(poiId.toString()), row["poi_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
                }
        }

    @Test
    fun `poi reservables returns aspira booking template from parent provider ref`() =
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

            val resp = client.get("/api/poi/$poiId/reservables")
            assertEquals(HttpStatusCode.OK, resp.status)
            val row =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["reservables"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertTrue(
                !row.containsKey("reservation_url"),
                "Aspira rows should expose reservation_url_template, not concrete reservation_url",
            )
            val template = row["reservation_url_template"]!!.jsonPrimitive.content
            assertTrue(template.startsWith("https://washington.goingtocamp.com/create-booking/results?"), template)
            assertTrue(template.contains("transactionLocationId=-2147483630"), template)
            assertTrue(template.contains("mapId=-2147483615"), template)
            assertTrue(template.contains("startDate={start_date}"), template)
            assertTrue(template.contains("endDate={end_date}"), template)
            assertTrue(template.contains("nights={nights}"), template)
            assertTrue(template.contains("resourceLocationId=-2147483624"), template)
        }

    @Test
    fun `poi reservables returns aspira booking template without dates`() =
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
                    providerRefJson = """{"mapId":-2147483615,"resourceLocationId":-2147483624}""",
                )
            link(reservableId, poiId)
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/$poiId/reservables")
            assertEquals(HttpStatusCode.OK, resp.status)
            val row =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["reservables"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertTrue(!row.containsKey("reservation_url"), "undated Aspira rows should not emit a concrete URL")
            val template = row["reservation_url_template"]!!.jsonPrimitive.content
            assertTrue(template.startsWith("https://washington.goingtocamp.com/create-booking/results?"), template)
            assertTrue(template.contains("startDate={start_date}"), template)
            assertTrue(template.contains("endDate={end_date}"), template)
            assertTrue(template.contains("nights={nights}"), template)
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
    fun `poi reservables rejects removed booking link params`() =
        testApplication {
            val poiId = seedPoi("upper-pines", "Upper Pines Campground")
            application { routing { reservableRoutes(ctx) } }

            assertEquals(HttpStatusCode.BadRequest, client.get("/api/poi/$poiId/reservables?start=2026-07-01").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/poi/$poiId/reservables?start_date=2026-07-01").status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .get("/api/poi/$poiId/reservables?start_date=2026-07-01&end_date=2026-07-03")
                    .status,
            )
        }

    @Test
    fun `poi reservables returns 404 for unknown poi`() =
        testApplication {
            application { routing { reservableRoutes(ctx) } }

            val resp = client.get("/api/poi/999999/reservables?type=site")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `poi reservables availability returns one envelope per linked reservable`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val a12 = seedReservable(vendorId = "330257", name = "A12")
            val b03 = seedReservable(vendorId = "330258", name = "B03")
            link(a12, poiId)
            link(b03, poiId)
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        AvailabilityCacheStoreImpl(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/reservables/availability?start_date=2026-07-01&end_date=2026-07-02")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(poiId, body["poi_id"]!!.jsonPrimitive.content.toLong())
            assertEquals("2026-07-01", body["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-02", body["end_date"]!!.jsonPrimitive.content)
            val rids =
                body["reservables"]!!
                    .jsonArray
                    .map { it.jsonObject["reservable_id"]!!.jsonPrimitive.content }
                    .sorted()
            assertEquals(listOf("site:recgov:330257", "site:recgov:330258"), rids)
            assertEquals(1, FakeReservationProvider.catalogAvailabilityCalls)
            assertEquals(0, FakeReservationProvider.reservableAvailabilityCalls)
        }

    @Test
    fun `poi reservables availability returns empty array when poi has no provider ref and no reservables`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "lonely-creek",
                    name = "Lonely Creek",
                )
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        AvailabilityCacheStoreImpl(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/reservables/availability?start_date=2026-07-01&end_date=2026-07-02")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(0, body["reservables"]!!.jsonArray.size)
            assertEquals(0, FakeReservationProvider.availabilityCalls)
            assertEquals(0, FakeReservationProvider.reservableAvailabilityCalls)
        }

    @Test
    fun `poi reservables availability falls back to provider matrix when no catalog reservables are linked`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "catalogless-creek",
                    name = "Catalogless Creek",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        AvailabilityCacheStoreImpl(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/reservables/availability?start_date=2026-07-01&end_date=2026-07-02")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val reservables = body["reservables"]!!.jsonArray

            assertEquals(1, reservables.size)
            assertEquals(
                "site:recgov:fake",
                reservables
                    .single()
                    .jsonObject["reservable_id"]!!
                    .jsonPrimitive
                    .content,
            )
            assertEquals(1, FakeReservationProvider.availabilityCalls)
            assertEquals(0, FakeReservationProvider.catalogAvailabilityCalls)
            assertEquals(0, FakeReservationProvider.reservableAvailabilityCalls)
        }

    @Test
    fun `poi reservables availability returns 404 for unknown poi`() =
        testApplication {
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        clock = fixedClock,
                    )
                }
            }
            val resp = client.get("/api/poi/999999/reservables/availability?start_date=2026-07-01&end_date=2026-07-02")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `poi reservables availability uses exclusive start and end date window`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines-window",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val a12 = seedReservable(vendorId = "330257", name = "A12")
            link(a12, poiId)
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/reservables/availability?start_date=2026-07-01&end_date=2026-07-04")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("2026-07-01", body["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-04", body["end_date"]!!.jsonPrimitive.content)
            val first = body["reservables"]!!.jsonArray.single().jsonObject
            assertEquals(3, first["availability"]!!.jsonArray.size)
        }

    @Test
    fun `availability routes ignore legacy days and min nights params`() =
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
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val windowQuery = "start_date=2026-07-01&end_date=2026-07-04"
            val poiStartStatus = client.get("/api/poi/$poiId/reservables/availability?start=2026-07-01").status
            val poiDaysStatus = client.get("/api/poi/$poiId/reservables/availability?$windowQuery&days=7").status
            val poiMinNightsStatus = client.get("/api/poi/$poiId/reservables/availability?$windowQuery&min_nights=2").status
            val poiMinNightsCamelStatus = client.get("/api/poi/$poiId/reservables/availability?$windowQuery&minNights=2").status

            assertAll(
                { assertEquals(HttpStatusCode.OK, poiStartStatus) },
                { assertEquals(HttpStatusCode.OK, poiDaysStatus) },
                { assertEquals(HttpStatusCode.OK, poiMinNightsStatus) },
                { assertEquals(HttpStatusCode.OK, poiMinNightsCamelStatus) },
            )
        }

    @Test
    fun `poi reservables availability defaults missing end date to seven day window`() =
        testApplication {
            val poiId =
                seedPoi(
                    sourceId = "upper-pines-default-end",
                    name = "Upper Pines Campground",
                    providerRefJson = """{"recgov_id":"232447"}""",
                )
            val a12 = seedReservable(vendorId = "330257", name = "A12")
            link(a12, poiId)
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/reservables/availability?start_date=2026-07-01")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("2026-07-01", body["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-08", body["end_date"]!!.jsonPrimitive.content)
            val first = body["reservables"]!!.jsonArray.single().jsonObject
            assertEquals(7, first["availability"]!!.jsonArray.size)
        }

    @Test
    fun `poi reservables availability filters by site type`() =
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
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        AvailabilityCacheStoreImpl(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp =
                client.get(
                    "/api/poi/$poiId/reservables/availability?start_date=2026-07-01&end_date=2026-07-03&site_type=STANDARD",
                )
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val rids =
                body["reservables"]!!
                    .jsonArray
                    .map { it.jsonObject["reservable_id"]!!.jsonPrimitive.content }
                    .sorted()
            assertEquals(listOf("site:recgov:330257", "site:recgov:330259"), rids)
            assertEquals(1, FakeReservationProvider.catalogAvailabilityCalls)
            assertEquals(0, FakeReservationProvider.reservableAvailabilityCalls)
        }

    @Test
    fun `bulk availability endpoint is not registered`() =
        testApplication {
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        AvailabilityCacheStoreImpl(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.post("/api/availability/bulk")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `bulk reservable availability resolves date windows per poi timezone`() =
        testApplication {
            val westPoi =
                seedPoi(
                    sourceId = "adak-same-provider-ref",
                    name = "Adak Same Provider Ref",
                    providerRefJson = """{"recgov_id":"232447"}""",
                    lng = -176.6368,
                    lat = 51.8800,
                )
            val eastPoi =
                seedPoi(
                    sourceId = "kiritimati-same-provider-ref",
                    name = "Kiritimati Same Provider Ref",
                    providerRefJson = """{"recgov_id":"232447"}""",
                    lng = -157.4278,
                    lat = 1.8721,
                )
            val westReservable = seedReservable(vendorId = "330301", name = "West A")
            val eastReservable = seedReservable(vendorId = "330302", name = "East A")
            link(westReservable, westPoi)
            link(eastReservable, eastPoi)
            val dateResolver =
                AvailabilityDateResolver(
                    clock = Clock.fixed(Instant.parse("2026-06-18T04:00:00Z"), ZoneOffset.UTC),
                )
            val reservablesRepo = ReservableRepo(ctx)
            val composer =
                ReservableAvailabilityComposer(
                    targets =
                        DbAvailabilityTargetResolver(
                            providerRefs = CampsiteProviderRepo(ctx),
                            reservablesRepo = reservablesRepo,
                            reservationProviders = fakeReservationProviders(),
                            dateResolver = dateResolver,
                        ),
                    dateResolver = dateResolver,
                )

            val availability =
                composer
                    .availabilityFor(
                        reservables =
                            listOf(
                                reservablesRepo.findByRid(ReservableId.parse("site:recgov:330301")!!)!!,
                                reservablesRepo.findByRid(ReservableId.parse("site:recgov:330302")!!)!!,
                            ),
                        startDate = null,
                        endDate = null,
                    ).associateBy { it.reservableId }

            assertEquals("2026-06-18", availability["site:recgov:330301"]!!.startDate)
            assertEquals("2026-06-25", availability["site:recgov:330301"]!!.endDate)
            assertEquals("2026-06-19", availability["site:recgov:330302"]!!.startDate)
            assertEquals("2026-06-26", availability["site:recgov:330302"]!!.endDate)
            assertEquals(2, FakeReservationProvider.catalogAvailabilityCalls)
        }

    @Test
    fun `poi reservables availability passes linked aspira child-map ref to provider`() =
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
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeAspiraReservationProviders(),
                        ReservableRepo(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/reservables/availability?start_date=2026-07-01&end_date=2026-07-02")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val byRid =
                body["reservables"]!!.jsonArray.associateBy {
                    it.jsonObject["reservable_id"]!!.jsonPrimitive.content
                }
            assertEquals(
                listOf("site:aspira_wa:-100", "site:aspira_wa:-200").sorted(),
                byRid.keys.sorted(),
            )
            // Each per-reservable response carries the child-map id (the
            // reservable's own mapId, not the POI's parent mapId).
            assertEquals(
                "-2147483615",
                byRid["site:aspira_wa:-100"]!!.jsonObject["map_id"]!!.jsonPrimitive.content,
            )
            assertEquals(
                "-2147483613",
                byRid["site:aspira_wa:-200"]!!.jsonObject["map_id"]!!.jsonPrimitive.content,
            )
        }

    @Test
    fun `poi reservables availability returns empty for walk-up poi with null provider ref`() =
        testApplication {
            // Mirrors POI 4931 in prod: a real campground POI from the recgov
            // ETL whose upstream `Reservable: false` left provider_ref null.
            // We must NOT 404 (the POI exists) — the FE just hides the matrix.
            val poiId = seedPoi(sourceId = "hull-creek", name = "Hull Creek", providerRefJson = null)
            application {
                routing {
                    availabilityRoutes(
                        CampsiteProviderRepo(ctx),
                        fakeReservationProviders(),
                        ReservableRepo(ctx),
                        clock = fixedClock,
                    )
                }
            }

            val resp = client.get("/api/poi/$poiId/reservables/availability?start_date=2026-07-01&end_date=2026-07-02")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(poiId, body["poi_id"]!!.jsonPrimitive.content.toLong())
            assertEquals(0, body["reservables"]!!.jsonArray.size)
            assertEquals(0, FakeReservationProvider.reservableAvailabilityCalls)
        }

    private fun seedPoi(
        sourceId: String,
        name: String,
        providerRefJson: String? = null,
        source: String = "test",
        lng: Double = -119.56,
        lat: Double = 37.74,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom,
                    region, properties, provider_ref, fetched_at
                ) VALUES (
                    ?, ?, 'campground', ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                )
                RETURNING id
                """.trimIndent(),
                source,
                sourceId,
                name,
                lng,
                lat,
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
        tagsJson: String? = null,
        providerRefJson: String? = null,
    ): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO reservables (
                    type, vendor, vendor_id, source, name, loop, site_type, raw, tags, provider_ref
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb
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
                tagsJson,
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

    private fun fakeReservationProviders(): ReservationProviderRegistry =
        ReservationProviderRegistry(
            adaptersBySource = mapOf("test" to FakeReservationProvider),
        )

    private fun fakeAspiraReservationProviders(): ReservationProviderRegistry =
        ReservationProviderRegistry(
            adaptersBySource = mapOf("aspira-wa-pins" to FakeAspiraReservationProvider),
        )

    private object FakeReservationProvider : ReservationProvider {
        var availabilityCalls: Int = 0
            private set
        var catalogAvailabilityCalls: Int = 0
            private set
        var reservableAvailabilityCalls: Int = 0
            private set

        fun reset() {
            availabilityCalls = 0
            catalogAvailabilityCalls = 0
            reservableAvailabilityCalls = 0
        }

        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = false,
                bookingHorizonDays = 365,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch {
            availabilityCalls++
            val ref = req.ref as ProviderRef.RecGov
            return fakeResponse(
                startDate = req.startDate,
                endDate = req.endDate,
                campgroundId = ref.recgovId,
                reservableId = null,
            )
        }

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
            catalogAvailabilityCalls++
            val ref = req.ref as ProviderRef.RecGov
            return fakeResponse(
                startDate = req.startDate,
                endDate = req.endDate,
                campgroundId = ref.recgovId,
                reservableId = null,
                availableIds = req.reservables.map { it.rid },
            )
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch {
            reservableAvailabilityCalls++
            return fakeResponse(
                startDate = req.startDate,
                endDate = req.endDate,
                campgroundId = null,
                reservableId = "site:recgov:${req.vendorId}",
            )
        }

        private fun fakeResponse(
            startDate: java.time.LocalDate,
            endDate: java.time.LocalDate,
            campgroundId: String?,
            reservableId: String?,
            availableIds: List<String>? = null,
        ): AvailabilityObservationBatch {
            val observedAt = Instant.now()
            val days =
                java.time.temporal.ChronoUnit.DAYS
                    .between(startDate, endDate)
                    .toInt()
            val ids = availableIds ?: listOf(reservableId ?: "site:recgov:fake")
            val observations =
                ids.flatMap { id ->
                    (0 until days).map { offset ->
                        ReservableDayObservation(
                            reservableId = id,
                            date = startDate.plusDays(offset.toLong()),
                            observedAt = observedAt,
                            status = AvailabilityStatus.AVAILABLE,
                        )
                    }
                }
            return AvailabilityObservationBatch(
                provider = "fake",
                startDate = startDate,
                endDate = endDate,
                observations = observations,
                cacheBlock = AvailabilityCacheBlock(hit = true, ageSeconds = 0, ttlSeconds = 60),
                campgroundId = campgroundId,
                reservableId = reservableId,
            )
        }
    }

    private object FakeAspiraReservationProvider : ReservationProvider {
        fun reset() = Unit

        override val id: ReservationProviderId = ReservationProviderId.ASPIRA
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = false,
                bookingHorizonDays = 365,
            )

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch {
            val ref = req.ref as ProviderRef.Aspira
            return fakeResponse(
                startDate = req.startDate,
                endDate = req.endDate,
                mapId = ref.mapId.toString(),
                reservableId = null,
                availableIds = emptyList(),
            )
        }

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch {
            val ref = req.ref as ProviderRef.Aspira
            return fakeResponse(
                startDate = req.startDate,
                endDate = req.endDate,
                mapId = ref.mapId.toString(),
                reservableId = null,
                availableIds = req.reservables.map { it.rid },
            )
        }

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch {
            val ref = req.ref as ProviderRef.Aspira
            return fakeResponse(
                startDate = req.startDate,
                endDate = req.endDate,
                mapId = ref.mapId.toString(),
                reservableId = "site:aspira_wa:${req.vendorId}",
                availableIds = listOf("site:aspira_wa:${req.vendorId}"),
            )
        }

        private fun fakeResponse(
            startDate: java.time.LocalDate,
            endDate: java.time.LocalDate,
            mapId: String,
            reservableId: String?,
            availableIds: List<String>,
        ): AvailabilityObservationBatch {
            val observedAt = Instant.now()
            val days =
                java.time.temporal.ChronoUnit.DAYS
                    .between(startDate, endDate)
                    .toInt()
            val observations =
                availableIds.flatMap { id ->
                    (0 until days).map { offset ->
                        ReservableDayObservation(
                            reservableId = id,
                            date = startDate.plusDays(offset.toLong()),
                            observedAt = observedAt,
                            status = AvailabilityStatus.AVAILABLE,
                        )
                    }
                }
            return AvailabilityObservationBatch(
                provider = "aspira",
                startDate = startDate,
                endDate = endDate,
                observations = observations,
                cacheBlock = AvailabilityCacheBlock(hit = true, ageSeconds = 0, ttlSeconds = 60),
                host = "washington.goingtocamp.com",
                mapId = mapId,
                reservableId = reservableId,
            )
        }
    }
}
