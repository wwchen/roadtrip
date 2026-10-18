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
import ca.floo.roadtrip.service.availability.AvailabilityServiceError
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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEST_MAX_POIS = 5
private const val TEST_FAN_OUT_CONCURRENCY = 4
private const val TEST_IP_POI_LIMIT_PER_MINUTE = 1_000
private const val RATE_LIMITED_POI_ID = 100L
private const val BEYOND_HORIZON_POI_ID = 101L

/**
 * A POI whose read waits until cancelled. With the per-POI timeout set far past the stream's
 * ([TEST_PER_POI_TIMEOUT_SECONDS] against [TEST_STREAM_TIMEOUT_MS]), only the stream's own deadline can end the check.
 */
private const val STALLED_POI_ID = 102L

/** Short enough to keep the test quick; the stalled read outlasts it, so it always fires. */
private const val TEST_STREAM_TIMEOUT_MS = 200L
private const val TEST_PER_POI_TIMEOUT_SECONDS = 30L
private const val UNKNOWN_CAMPSITE_POI_ID = 999_999L
private const val FORWARDED_FOR_HEADER = "X-Forwarded-For"

/** The stay check's path. */
private const val STAYS_PATH = "/api/pois/availability/stays"

/** The per-campsite scan's path, and the stay check's earlier one: neither answers anything now. */
private const val REMOVED_SCAN_PATH = "/api/pois/availability/bulk"
private const val REMOVED_STREAM_PATH = "/api/pois/availability/bulk/stream"

/** [FakePoiAvailabilitySliceLookup]'s campsite id is derived from the poi id by this factor. */
private const val FAKE_SLICE_CAMPSITE_ID_MULTIPLIER = 1000L

private val windowStart: LocalDate = LocalDate.of(2026, 9, 1)
private val windowEnd: LocalDate = LocalDate.of(2026, 9, 8)

/** rec.gov's horizon, so the faked slice's ceiling is a real provider's. */
private const val BOOKING_HORIZON_DAYS = 180L
private val observedAt: Instant = Instant.parse("2026-08-20T00:00:00Z")

/** The clock the max-age tests read, so the cutoff a request derives is an exact instant. */
private val maxAgeNow: Instant = Instant.parse("2026-10-12T12:00:00Z")

/** The endpoint's default and ceiling, and its floor: far enough apart that a clamp cannot be mistaken for either end. */
private val maxAgeCeiling: Duration = Duration.ofHours(2)
private val maxAgeFloor: Duration = Duration.ofMinutes(1)

/** Strictly inside the range, and neither end of it nor the default; and a year, which only the ceiling can answer. */
private const val MAX_AGE_INSIDE_RANGE_SECONDS = 600L
private const val MAX_AGE_A_YEAR_SECONDS = 31_536_000L

class BulkAvailabilityRoutesTest {
    @Test
    fun `the stay check answers at its own path`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(ContentType.Text.EventStream, resp.contentType()?.withoutParameters())
            assertEquals(listOf("answer", "done"), streamEvents(resp.bodyAsText()).map { it.first })
        }

    @Test
    fun `the per-campsite scan is gone`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(REMOVED_SCAN_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `the old stream path is gone`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(REMOVED_STREAM_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `rejects a request with more poi ids than the configured cap`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(STAYS_PATH) {
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
                client.post(STAYS_PATH) {
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
                client.post(STAYS_PATH) {
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
                client.post(STAYS_PATH) {
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
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1,2],"start_date":"$windowEnd","end_date":"$windowStart"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("end_before_start", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects an empty poi_ids list`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(STAYS_PATH) {
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
                client.post(STAYS_PATH) {
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
                client.post(STAYS_PATH) {
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
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":"x"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_request", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `the stream answers each poi as an event, then says it is done`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(STAYS_PATH) {
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
            assertEquals("answered", first["outcome"]!!.jsonPrimitive.content)
            val stay = first["stay"]!!.jsonObject
            assertEquals("full", stay["status"]!!.jsonPrimitive.content)
            assertEquals(0, stay["open_sites"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, stay["checked_sites"]!!.jsonPrimitive.content.toInt())
            assertEquals(observedAt.toString(), stay["observed_at"]!!.jsonPrimitive.content)
            assertEquals(false, stay["from_cache"]!!.jsonPrimitive.boolean)
            val failed = answers.single { it["poi_id"]!!.jsonPrimitive.content == "$RATE_LIMITED_POI_ID" }
            assertEquals("failed", failed["outcome"]!!.jsonPrimitive.content)
            assertEquals("rate_limited", failed["reason"]!!.jsonPrimitive.content)
            val done = Json.parseToJsonElement(events.last().second).jsonObject
            assertEquals("complete", done["outcome"]!!.jsonPrimitive.content)
            assertTrue("answered" !in done, "the count is gone: the outcome is what the server alone can say")
        }

    @Test
    fun `a stream cut short by its deadline still answers every poi, and done says deadline_reached`() =
        testApplication {
            application {
                routeTestApplication {
                    bulkAvailabilityRoutesUnderTest(
                        streamTimeout = Duration.ofMillis(TEST_STREAM_TIMEOUT_MS),
                        perPoiTimeout = Duration.ofSeconds(TEST_PER_POI_TIMEOUT_SECONDS),
                    )
                }
            }

            val resp =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1,$STALLED_POI_ID],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            val events = streamEvents(resp.bodyAsText())
            assertEquals(listOf("answer", "answer", "done"), events.map { it.first })
            val stalled =
                events
                    .filter { it.first == "answer" }
                    .map { Json.parseToJsonElement(it.second).jsonObject }
                    .single { it["poi_id"]!!.jsonPrimitive.content == "$STALLED_POI_ID" }
            assertEquals("timed_out", stalled["outcome"]!!.jsonPrimitive.content)
            val done = Json.parseToJsonElement(events.last().second).jsonObject
            assertEquals("deadline_reached", done["outcome"]!!.jsonPrimitive.content)
        }

    @Test
    fun `max_age_seconds below the floor clamps up to it`() =
        testApplication {
            val cutoffs = mutableListOf<Instant?>()
            application { routeTestApplication { maxAgeRoutesUnderTest(cutoffs) } }

            // Zero is the case the floor exists for: it would refetch every campground on every request.
            val zero = postStay(maxAgeSeconds = "0")
            val negative = postStay(maxAgeSeconds = "-1")

            assertEquals(HttpStatusCode.OK, zero.status)
            assertEquals(HttpStatusCode.OK, negative.status)
            assertEquals(listOf<Instant?>(maxAgeNow.minus(maxAgeFloor), maxAgeNow.minus(maxAgeFloor)), cutoffs)
        }

    @Test
    fun `max_age_seconds above the ceiling clamps down to it`() =
        testApplication {
            val cutoffs = mutableListOf<Instant?>()
            application { routeTestApplication { maxAgeRoutesUnderTest(cutoffs) } }

            val resp = postStay(maxAgeSeconds = "$MAX_AGE_A_YEAR_SECONDS")

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf<Instant?>(maxAgeNow.minus(maxAgeCeiling)), cutoffs)
        }

    @Test
    fun `an absent max_age_seconds uses the default`() =
        testApplication {
            val cutoffs = mutableListOf<Instant?>()
            application { routeTestApplication { maxAgeRoutesUnderTest(cutoffs) } }

            val resp = postStay(maxAgeSeconds = null)

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf<Instant?>(maxAgeNow.minus(maxAgeCeiling)), cutoffs)
        }

    @Test
    fun `a max_age_seconds inside the range passes through unchanged`() =
        testApplication {
            val cutoffs = mutableListOf<Instant?>()
            application { routeTestApplication { maxAgeRoutesUnderTest(cutoffs) } }

            // Neither end of the range, nor the default: a clamp that always answered one of them fails here.
            val resp = postStay(maxAgeSeconds = "$MAX_AGE_INSIDE_RANGE_SECONDS")

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf<Instant?>(maxAgeNow.minusSeconds(MAX_AGE_INSIDE_RANGE_SECONDS)), cutoffs)
        }

    @Test
    fun `a max_age_seconds exactly at the floor is kept, and exactly at the ceiling is accepted`() =
        testApplication {
            val cutoffs = mutableListOf<Instant?>()
            application { routeTestApplication { maxAgeRoutesUnderTest(cutoffs) } }

            // Only the floor half discriminates: a clamp that dropped an equal value would fall back to
            // the ceiling and fail. The ceiling is also the default (one config value, by design: the
            // range is floor..default), so "sent the ceiling, kept" and "sent nothing, defaulted" give
            // the same cutoff and this half cannot tell them apart. It pins only that the ceiling is
            // accepted; do not split the config value to sharpen it.
            postStay(maxAgeSeconds = "${maxAgeFloor.seconds}")
            postStay(maxAgeSeconds = "${maxAgeCeiling.seconds}")

            assertEquals(listOf<Instant?>(maxAgeNow.minus(maxAgeFloor), maxAgeNow.minus(maxAgeCeiling)), cutoffs)
        }

    @Test
    fun `a clamped value is not a bad request`() =
        testApplication {
            application { routeTestApplication { maxAgeRoutesUnderTest() } }

            for (maxAge in listOf("0", "-1", "$MAX_AGE_A_YEAR_SECONDS", null)) {
                val resp = postStay(maxAgeSeconds = maxAge)
                assertEquals(HttpStatusCode.OK, resp.status, "max_age_seconds=$maxAge")
                assertEquals(ContentType.Text.EventStream, resp.contentType()?.withoutParameters(), "max_age_seconds=$maxAge")
                assertEquals(listOf("answer", "done"), streamEvents(resp.bodyAsText()).map { it.first }, "max_age_seconds=$maxAge")
            }
        }

    @Test
    fun `the removed fresh field no longer changes the age`() =
        testApplication {
            val cutoffs = mutableListOf<Instant?>()
            application { routeTestApplication { maxAgeRoutesUnderTest(cutoffs) } }

            // Unknown keys are ignored on the wire, so an old client still gets an answer, at the default age.
            val resp =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd","fresh":true}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf<Instant?>(maxAgeNow.minus(maxAgeCeiling)), cutoffs)
        }

    @Test
    fun `an answer event carries outcome and omits the fields that do not apply`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"poi_ids":[1,$BEYOND_HORIZON_POI_ID,$RATE_LIMITED_POI_ID],""" +
                            """"start_date":"$windowStart","end_date":"$windowEnd"}""",
                    )
                }

            val answers =
                streamEvents(resp.bodyAsText())
                    .filter { it.first == "answer" }
                    .associate { (_, data) ->
                        val answer = Json.parseToJsonElement(data).jsonObject
                        answer["poi_id"]!!.jsonPrimitive.content.toLong() to answer
                    }
            val answered = answers.getValue(1L)
            assertEquals("answered", answered["outcome"]!!.jsonPrimitive.content)
            assertTrue("stay" in answered)
            assertTrue("reason" !in answered)
            assertTrue("retryable" !in answered)

            val refused = answers.getValue(BEYOND_HORIZON_POI_ID)
            assertEquals("refused", refused["outcome"]!!.jsonPrimitive.content)
            assertEquals("beyond_booking_horizon", refused["reason"]!!.jsonPrimitive.content)
            assertTrue("stay" !in refused)
            assertTrue("retryable" !in refused)

            val failed = answers.getValue(RATE_LIMITED_POI_ID)
            assertEquals("failed", failed["outcome"]!!.jsonPrimitive.content)
            assertEquals("rate_limited", failed["reason"]!!.jsonPrimitive.content)
            assertEquals(true, failed["retryable"]!!.jsonPrimitive.boolean)
            assertTrue("stay" !in failed)
        }

    @Test
    fun `the stream refuses a bad request with JSON, before any event`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(ContentType.Application.Json, resp.contentType()?.withoutParameters())
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_date_window", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `rejects a non-positive group_size`() =
        testApplication {
            application { routeTestApplication { bulkAvailabilityRoutesUnderTest() } }

            val resp =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"group_size":0,"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("bad_group_size", body["error"]!!.jsonPrimitive.content)
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
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }
            assertEquals(HttpStatusCode.OK, first.status)

            val second =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, second.status)
            val body = Json.parseToJsonElement(second.bodyAsText()).jsonObject
            assertEquals("ip_throttled", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a request is charged for every campground it asks about`() =
        testApplication {
            application {
                routeTestApplication {
                    bulkAvailabilityRoutesUnderTest(rateLimit = IpRateLimiter(perMinute = 3, nowMs = { 0L }))
                }
            }

            suspend fun streamOf(poiIds: String) =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[$poiIds],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }

            assertEquals(HttpStatusCode.OK, streamOf("1,2").status)
            // One left: a second pair would be four campgrounds' upstream calls in the minute.
            assertEquals(HttpStatusCode.ServiceUnavailable, streamOf("1,2").status)
            assertEquals(HttpStatusCode.OK, streamOf("1").status)
        }

    @Test
    fun `a malformed request is refused for what it is, and spends none of the caller's budget`() =
        testApplication {
            application {
                routeTestApplication {
                    bulkAvailabilityRoutesUnderTest(rateLimit = IpRateLimiter(perMinute = 1, nowMs = { 0L }))
                }
            }

            val malformed =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)

            val valid =
                client.post(STAYS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"}""")
                }
            assertEquals(HttpStatusCode.OK, valid.status)
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
                client.post(STAYS_PATH) {
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

    /** One stay-stream POST for POI 1, with [maxAgeSeconds] written into the body as given, or left out when null. */
    private suspend fun ApplicationTestBuilder.postStay(maxAgeSeconds: String?): HttpResponse =
        client.post(STAYS_PATH) {
            contentType(ContentType.Application.Json)
            val maxAge = maxAgeSeconds?.let { ""","max_age_seconds":$it""" }.orEmpty()
            setBody("""{"poi_ids":[1],"start_date":"$windowStart","end_date":"$windowEnd"$maxAge}""")
        }

    /**
     * The routes over a config whose floor, default and ceiling are three distinct, known ages and a clock
     * frozen at [maxAgeNow], recording each cutoff the controller hands the lookup into [cutoffs].
     */
    private fun Route.maxAgeRoutesUnderTest(cutoffs: MutableList<Instant?> = mutableListOf()) =
        bulkAvailabilityRoutesUnderTest(
            defaultMaxAge = maxAgeCeiling,
            minMaxAge = maxAgeFloor,
            clock = Clock.fixed(maxAgeNow, ZoneOffset.UTC),
            cutoffs = cutoffs,
        )

    private fun Route.bulkAvailabilityRoutesUnderTest(
        rateLimit: IpRateLimiter? = null,
        streamTimeout: Duration = BulkAvailabilityConfig.default.streamTimeout,
        perPoiTimeout: Duration = BulkAvailabilityConfig.default.perPoiTimeout,
        defaultMaxAge: Duration = Duration.ZERO,
        minMaxAge: Duration = Duration.ZERO,
        clock: Clock = Clock.systemUTC(),
        cutoffs: MutableList<Instant?>? = null,
    ) {
        val config =
            BulkAvailabilityConfig(
                maxPois = TEST_MAX_POIS,
                fanOutConcurrency = TEST_FAN_OUT_CONCURRENCY,
                defaultMaxAge = defaultMaxAge,
                ipPoiLimitPerMinute = TEST_IP_POI_LIMIT_PER_MINUTE,
                minMaxAge = minMaxAge,
                streamTimeout = streamTimeout,
                perPoiTimeout = perPoiTimeout,
            )
        val controller =
            BulkAvailabilityController(
                sliceLookup = FakePoiAvailabilitySliceLookup(cutoffs),
                config = config,
                clock = clock,
            )
        if (rateLimit != null) {
            bulkAvailabilityRoutes(controller, config, rateLimit)
        } else {
            bulkAvailabilityRoutes(controller, config)
        }
    }
}

/**
 * Registering `POST /api/pois/availability/stays` sits right next to the
 * pre-existing `GET /api/pois/{id}/campsites/availability` under `/api/pois`.
 * Ktor must keep routing a numeric POI id through the `{id}` parameter node
 * rather than losing it to the new literal `availability` segment.
 */
class BulkAvailabilityRouteCollisionTest : SharedDbTest() {
    @Test
    fun `the pre-existing detail endpoint still routes correctly once the stay check is registered`() =
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
            // or a 405/400 from the stay check's parser. A structured
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
    private val cutoffs: MutableList<Instant?>? = null,
) : PoiAvailabilitySliceLookup {
    override suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant?,
    ): PoiAvailabilitySlice {
        cutoffs?.add(freshAtOrAfter)
        if (poiId == RATE_LIMITED_POI_ID) throw AvailabilityProviderError.RateLimited()
        if (poiId == STALLED_POI_ID) awaitCancellation()
        if (poiId == BEYOND_HORIZON_POI_ID) {
            throw AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon(windowStart.plusDays(BOOKING_HORIZON_DAYS))
        }
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
            pollingSupported = true,
        )
    }
}
