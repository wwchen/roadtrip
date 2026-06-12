package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.ProviderRef
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
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
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
import kotlin.test.assertEquals

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
                    )
                }
            }

            val resp = client.get("/api/reservable/site:recgov:330257/availability?start=2026-07-01&days=1")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("fake", body["provider"]!!.jsonPrimitive.content)
            assertEquals("site:recgov:330257", body["reservable_id"]!!.jsonPrimitive.content)
            val firstDate =
                body["availability"]!!
                    .jsonArray
                    .single()
                    .jsonObject["date"]!!
                    .jsonPrimitive
                    .content
            assertEquals("2026-07-01", firstDate)
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
                    'site', 'recgov', ?, 'federal-campsites', ?, ?, ?, ?::jsonb
                )
                RETURNING id
                """.trimIndent(),
                vendorId,
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
