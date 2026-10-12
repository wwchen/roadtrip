package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.config.CampsiteAvailabilityConfig
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.fixtures.testCampsiteCatalogService
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.route.routeTestApplication
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.availability.BulkAvailabilityController
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityController
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.PoiAvailabilitySlice
import ca.floo.roadtrip.service.availability.PoiAvailabilitySliceLookup
import ca.floo.roadtrip.service.availability.ProviderCooldownTracker
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEST_MAX_POIS = 5
private const val TEST_FAN_OUT_CONCURRENCY = 4
private const val TEST_IP_RATE_LIMIT_PER_MINUTE = 10
private const val RATE_LIMITED_POI_ID = 100L
private const val UNKNOWN_CAMPSITE_POI_ID = 999_999L
private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"

/** [FakePoiAvailabilitySliceLookup]'s campsite id is derived from the poi id by this factor. */
private const val FAKE_SLICE_CAMPSITE_ID_MULTIPLIER = 1000L

private val windowStart: LocalDate = LocalDate.of(2026, 9, 1)
private val windowEnd: LocalDate = LocalDate.of(2026, 9, 8)

/** rec.gov's horizon, so the faked slice's ceiling is a real provider's. */
private const val BOOKING_HORIZON_DAYS = 180L
private val observedAt: Instant = Instant.parse("2026-08-20T00:00:00Z")

class BulkAvailabilityRoutesTest {
    @Test
    fun `rejects a request with more poi ids than the configured cap`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1,2,3,4,5,6]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("too_many_pois", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects an unparseable date`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"not-a-date"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_date_window", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a request omitting both dates`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_date_window", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a request missing only the end date`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_date_window", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects an inverted window before fanning out`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1,2],"start_date":"$windowEnd","end_date":"$windowStart"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("end_before_start", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a non-positive min_nights`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"min_nights":0}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_min_nights", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects an empty poi_ids list`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a site_type outside the wire vocabulary and names the value`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"poi_ids":[1],"start_date":"2026-07-04","end_date":"2026-07-06",""" +
                            """"site_type":["tent","TENT ONLY NONELECTRIC"]}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
            assertTrue(body["detail"]!!.jsonPrimitive.content.contains("TENT ONLY NONELECTRIC"))
        }

    @Test
    fun `rejects syntactically malformed json without leaking the parser's message`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a wrong-typed field without leaking the serializer's message`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":"x"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `returns 200 with per-poi entries when one poi fails`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"poi_ids":[1,$RATE_LIMITED_POI_ID],""" +
                            """"start_date":"$windowStart","end_date":"$windowEnd"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val pois = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["pois"]!!.jsonArray
            assertEquals(2, pois.size)
            assertEquals("rate_limited", pois[1].jsonObject["error"]!!.jsonPrimitive.content)

            // The successful poi's stream: one campsite, one cell keyed by its own id.
            val successfulPoiCampsiteId = 1L * FAKE_SLICE_CAMPSITE_ID_MULTIPLIER
            val cells =
                pois[0]
                    .jsonObject["campsites"]!!
                    .jsonArray[0]
                    .jsonObject["availability"]!!
                    .jsonArray[0]
                    .jsonObject["cells"]!!
                    .jsonObject
            assertEquals(setOf(successfulPoiCampsiteId.toString()), cells.keys)
            val cell = cells[successfulPoiCampsiteId.toString()]!!.jsonObject
            assertEquals("available", cell["status"]!!.jsonPrimitive.content)
            assertEquals(false, cell["watchable"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `the stream answers each poi as an event, then says it is done`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk/stream") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"poi_ids":[1,$RATE_LIMITED_POI_ID],""" +
                            """"start_date":"$windowStart","end_date":"$windowEnd"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(ContentType.Text.EventStream, resp.contentType()?.withoutParameters())
            val events = streamEvents(resp.bodyAsText())
            assertEquals(listOf("answer", "answer", "done"), events.map { it.first })
            val answers = events.filter { it.first == "answer" }.map { Json.parseToJsonElement(it.second).jsonObject }
            // The one campsite is bookable the first night and taken the second, so
            // nothing is open for the whole window and the provider did answer.
            val first = answers.single { it["poi_id"]!!.jsonPrimitive.content == "1" }
            val summary = first["summary"]!!.jsonObject
            assertEquals("full", summary["status"]!!.jsonPrimitive.content)
            assertEquals(0, summary["open_sites"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, summary["checked_sites"]!!.jsonPrimitive.content.toInt())
            val failed = answers.single { it["poi_id"]!!.jsonPrimitive.content == "$RATE_LIMITED_POI_ID" }
            assertEquals("rate_limited", failed["error"]!!.jsonPrimitive.content)
            assertTrue("summary" !in failed)
            assertEquals(
                2,
                Json
                    .parseToJsonElement(events.last().second)
                    .jsonObject["answered"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `the stream refuses a bad request with JSON, before any event`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk/stream") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(ContentType.Application.Json, resp.contentType()?.withoutParameters())
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_date_window", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `the stream and the scan share one per-caller limit`() =
        testApplication {
            application {
                routeTestApplication {
                    bulkAvailabilityRoutesUnderTest(rateLimit = IpRateLimiter(perMinute = 1, nowMs = { 0L }))
                }
            }
            val body = """{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}"""

            val scan =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, scan.status)

            val stream =
                client.post("/api/pois/availability/bulk/stream") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, stream.status)
            val refused = Json.parseToJsonElement(stream.bodyAsText()).jsonObject
            assertEquals("ip_throttled", refused["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a non-positive group_size`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"group_size":0,"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_group_size", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a reserved cell is watchable when the provider can be polled`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            assertEquals(true, reservedCellWatchable(client))
        }

    @Test
    fun `a reserved cell is not watchable when the provider cannot be polled`() =
        testApplication {
            application {
                routeTestApplication { bulkAvailabilityRoutesUnderTest(pollingSupported = false) }
            }

            // The same cell the detail endpoint would call unwatchable: `watchable`
            // is one predicate, and polling support is part of it on both endpoints.
            assertEquals(false, reservedCellWatchable(client))
        }

    /** The bulk cell for the window's second (reserved) night on the one campsite. */
    private suspend fun reservedCellWatchable(client: HttpClient): Boolean {
        val resp =
            client.post("/api/pois/availability/bulk") {
                contentType(ContentType.Application.Json)
                setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
            }
        assertEquals(HttpStatusCode.OK, resp.status)
        val campsiteId = 1L * FAKE_SLICE_CAMPSITE_ID_MULTIPLIER
        val day =
            Json
                .parseToJsonElement(resp.bodyAsText())
                .jsonObject["pois"]!!
                .jsonArray[0]
                .jsonObject["campsites"]!!
                .jsonArray[0]
                .jsonObject["availability"]!!
                .jsonArray[1]
                .jsonObject
        assertEquals("reserved", day["status"]!!.jsonPrimitive.content)
        return day["cells"]!!
            .jsonObject[campsiteId.toString()]!!
            .jsonObject["watchable"]!!
            .jsonPrimitive.boolean
    }

    @Test
    fun `throttles by ip`() =
        testApplication {
            application {
                routeTestApplication {
                    // One token, frozen clock: the second request must be denied.
                    bulkAvailabilityRoutesUnderTest(rateLimit = IpRateLimiter(perMinute = 1, nowMs = { 0L }))
                }
            }

            val first =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }
            assertEquals(HttpStatusCode.OK, first.status)

            val second =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, second.status)
            val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals("ip_throttled", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `behind a proxy each caller is throttled on its own, by the address the edge appended`() =
        testApplication {
            application {
                routeTestApplication {
                    // One token each, frozen clock. Every request shares one socket peer, as
                    // every visitor does behind a proxy; X-Forwarded-For tells them apart.
                    bulkAvailabilityRoutesUnderTest(rateLimit = IpRateLimiter(perMinute = 1, nowMs = { 0L }))
                }
            }

            suspend fun postVia(forwardedFor: String) =
                client.post("/api/pois/availability/bulk") {
                    contentType(ContentType.Application.Json)
                    header(FORWARDED_FOR_HEADER, forwardedFor)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            assertEquals(HttpStatusCode.OK, postVia("203.0.113.7").status)
            assertEquals(HttpStatusCode.OK, postVia("198.51.100.4").status)
            // A caller that writes its own entry, behind an inner hop that appends its
            // own private address, still lands on the one the edge appended.
            assertEquals(HttpStatusCode.ServiceUnavailable, postVia("192.0.2.99, 203.0.113.7, 172.18.0.5").status)
        }

    private fun Route.bulkAvailabilityRoutesUnderTest(
        rateLimit: IpRateLimiter? = null,
        pollingSupported: Boolean = true,
    ) {
        val config =
            BulkAvailabilityConfig(
                maxPois = TEST_MAX_POIS,
                fanOutConcurrency = TEST_FAN_OUT_CONCURRENCY,
                tolerance = Duration.ZERO,
                ipRateLimitPerMinute = TEST_IP_RATE_LIMIT_PER_MINUTE,
            )
        val controller =
            BulkAvailabilityController(
                sliceLookup = FakePoiAvailabilitySliceLookup(pollingSupported),
                config = config,
            )
        if (rateLimit != null) {
            bulkAvailabilityRoutes(controller, config, rateLimit)
        } else {
            bulkAvailabilityRoutes(controller, config)
        }
    }
}

/**
 * Registering `POST /api/pois/availability/bulk` sits right next to the
 * pre-existing `GET /api/pois/{id}/campsites/availability` under `/api/pois`.
 * Ktor must keep routing a numeric POI id through the `{id}` parameter node
 * rather than losing it to the new literal `availability` segment.
 */
class BulkAvailabilityRouteCollisionTest : SharedDbTest() {
    @Test
    fun `the pre-existing detail endpoint still routes correctly once bulk is registered`() =
        testApplication {
            application {
                routeTestApplication {
                    campsiteRoutes(campsiteAvailabilityController(), CampsiteAvailabilityConfig.default)
                    bulkAvailabilityRoutes(
                        BulkAvailabilityController(
                            sliceLookup = FakePoiAvailabilitySliceLookup(),
                            config = BulkAvailabilityConfig.default,
                        ),
                        BulkAvailabilityConfig.default,
                    )
                }
            }

            val resp = client.get("/api/pois/$UNKNOWN_CAMPSITE_POI_ID/campsites/availability")

            // A route-collision would surface as Ktor's bare 404 (no JSON body)
            // or a 405/400 from the bulk route's parser. A structured
            // `not_found` body proves the {id} detail handler ran.
            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("not_found", body["error"]!!.jsonPrimitive.content)
        }

    private fun campsiteAvailabilityController(): CampsiteAvailabilityController {
        val campsitesRepo = CampsiteRepo(ctx)
        val campgroundRepo = CampgroundRepo(ctx)
        val dateResolver = AvailabilityDateResolver(PoiRepo(ctx))
        val providers: List<AvailabilityProvider> = emptyList()
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = campgroundRepo,
                availabilityProviders = providers,
                dateResolver = dateResolver,
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        return CampsiteAvailabilityController(
            campgroundRepo = campgroundRepo,
            campsitesRepo = campsitesRepo,
            catalogService = testCampsiteCatalogService(ctx, campsitesRepo, targets),
            availabilityService =
                CampsiteAvailabilityService(
                    availabilityProviders = providers,
                    dateResolver = dateResolver,
                    failoverFetcher = FailoverAvailabilityFetcher(cooldowns = ProviderCooldownTracker(cooldown = Duration.ofMinutes(1))),
                    bookingHorizons = BookingHorizonResolver(providers, dateResolver),
                    availabilityRepo = AvailabilityRepo(ctx),
                ),
            dateResolver = dateResolver,
            watchCapabilityService =
                WatchCapabilityService(
                    availabilityTargets = targets,
                    bookingTargets = AvailabilityBookingTargetResolver(BookingAdapterRegistry(emptyList())),
                    tenants = shippedTenantRegistry(),
                ),
        )
    }
}

/**
 * A stream body's `(event, data)` pairs, in order; keep-alive comments and blank
 * lines dropped. Lines may end `\r\n` — Ktor's writer ends them so, which the
 * format allows.
 */
private fun streamEvents(body: String): List<Pair<String, String>> =
    body.replace("\r\n", "\n").split("\n\n").mapNotNull { block ->
        val lines = block.lines().filter { it.isNotEmpty() && !it.startsWith(":") }
        val event = lines.firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim() ?: return@mapNotNull null
        event to lines.filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
    }

/**
 * Stands in for [CampsiteAvailabilityController.poiAvailabilitySlice] without
 * a database. Every POI resolves to one campsite with a single bookable night
 * followed by a reserved one, except [RATE_LIMITED_POI_ID], which reports an
 * upstream failure.
 */
private class FakePoiAvailabilitySliceLookup(
    private val pollingSupported: Boolean = true,
) : PoiAvailabilitySliceLookup {
    override suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant?,
    ): PoiAvailabilitySlice {
        if (poiId == RATE_LIMITED_POI_ID) throw AvailabilityProviderError.RateLimited()
        val campsite = campsiteFixture(id = poiId * FAKE_SLICE_CAMPSITE_ID_MULTIPLIER, campgroundId = poiId)
        val observations =
            listOf(
                CampsiteDayObservation(campsite.id, windowStart, observedAt, AvailabilityStatus.AVAILABLE),
                CampsiteDayObservation(
                    campsite.id,
                    windowStart.plusDays(1),
                    observedAt,
                    AvailabilityStatus.RESERVED,
                ),
            )
        return PoiAvailabilitySlice(
            poiId = poiId,
            startDate = windowStart,
            endDate = windowEnd,
            earliestDate = windowStart,
            latestDate = windowStart.plusDays(BOOKING_HORIZON_DAYS),
            allCampsites = listOf(campsite),
            campsites = listOf(campsite),
            batch =
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = windowStart,
                    endDate = windowEnd,
                    observations = observations,
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
                ),
            pollingSupported = pollingSupported,
        )
    }
}
