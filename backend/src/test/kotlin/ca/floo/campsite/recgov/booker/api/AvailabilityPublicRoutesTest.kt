package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.campsite.recgov.booker.poller.Campsite
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-contract tests for GET /api/campsite/availability/{recgov_id}. Backed
 * by a fake fetchMonth lambda so no rec.gov calls happen. Asserts:
 *   - input validation (regex, days clamp, unknown campground)
 *   - state classification (success / zero_available / closed_for_season / empty)
 *   - error mapping (429 → 503 rate_limited, 5xx → 503 upstream_5xx)
 *   - per-IP rate limiting (>10/min → 503 ip_throttled)
 *   - JSON contract shape
 */
class AvailabilityPublicRoutesTest {
    private fun campsiteWith(availabilities: Map<String, String>): Campsite =
        Campsite(
            id = "100",
            site = "A1",
            loop = "Loop A",
            campsiteType = "STANDARD",
            maxNumPeople = 4,
            equipmentTypes = emptyList(),
            availabilities = availabilities,
        )

    /** today + offset → "2026-MM-DDT00:00:00Z" — rec.gov's keying shape. */
    private fun futureKey(offsetDays: Long): String =
        java.time.LocalDate
            .now(java.time.ZoneOffset.UTC)
            .plusDays(offsetDays)
            .toString() + "T00:00:00Z"

    private fun cacheReturning(map: Map<String, Campsite>): CachedAvailability =
        CachedAvailability(
            fetchMonth = { _, _ -> map },
            ttl = Duration.ofMinutes(10),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    private fun parseJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `success state with availability returns success JSON shape`() =
        testApplication {
            val map =
                mapOf(
                    "100" to
                        campsiteWith(
                            mapOf(
                                futureKey(0) to "Available",
                                futureKey(1) to "Reserved",
                                futureKey(2) to "Available",
                            ),
                        ),
                )
            val cache = cacheReturning(map)
            application { routing { availabilityPublicRoutes(cache) } }
            val r = client.get("/api/campsite/availability/232447?days=7")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = parseJson(r.bodyAsText())
            assertEquals("success", body["state"]!!.jsonPrimitive.content)
            assertEquals("232447", body["campground_id"]!!.jsonPrimitive.content)
            assertEquals(
                7,
                body["window"]!!
                    .jsonObject["days"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(7, body["availability"]!!.jsonArray.size)
            assertTrue(body["summary"]!!.jsonPrimitive.content.contains("nights available"))
            assertEquals(false, body["cache"]!!.jsonObject["hit"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `zero_available state when all days are booked`() =
        testApplication {
            val booked = (0..6L).associate { futureKey(it) to "Reserved" }
            val cache = cacheReturning(mapOf("100" to campsiteWith(booked)))
            application { routing { availabilityPublicRoutes(cache) } }
            val r = client.get("/api/campsite/availability/232447?days=7")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = parseJson(r.bodyAsText())
            assertEquals("zero_available", body["state"]!!.jsonPrimitive.content)
            assertTrue(body["summary"]!!.jsonPrimitive.content.contains("Fully booked"))
        }

    @Test
    fun `closed_for_season state when all days are Closed`() =
        testApplication {
            val closed = (0..6L).associate { futureKey(it) to "Closed" }
            val cache = cacheReturning(mapOf("100" to campsiteWith(closed)))
            application { routing { availabilityPublicRoutes(cache) } }
            val r = client.get("/api/campsite/availability/232447?days=7")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = parseJson(r.bodyAsText())
            assertEquals("closed_for_season", body["state"]!!.jsonPrimitive.content)
            assertEquals("Closed for season", body["summary"]!!.jsonPrimitive.content)
        }

    @Test
    fun `empty state when no campsites returned`() =
        testApplication {
            val cache = cacheReturning(emptyMap())
            application { routing { availabilityPublicRoutes(cache) } }
            val r = client.get("/api/campsite/availability/232447?days=7")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = parseJson(r.bodyAsText())
            assertEquals("empty", body["state"]!!.jsonPrimitive.content)
        }

    @Test
    fun `bad recgov_id returns 400 bad_recgov_id`() =
        testApplication {
            val cache = cacheReturning(emptyMap())
            application { routing { availabilityPublicRoutes(cache) } }
            // Path-traversal-shaped inputs ("../etc", empty) get handled by Ktor's
            // routing layer before reaching our regex check; what matters is they
            // never reach AvailabilityClient.fetchMonth. Test only the inputs
            // that route to our handler with a malformed recgov_id.
            for (bad in listOf("abc", "12345678901", "id-with-dash")) {
                val r = client.get("/api/campsite/availability/$bad")
                assertEquals(HttpStatusCode.BadRequest, r.status, "expected 400 for '$bad'")
                assertTrue(r.bodyAsText().contains("bad_recgov_id"))
            }
        }

    @Test
    fun `bad days returns 400 bad_days`() =
        testApplication {
            val cache = cacheReturning(emptyMap())
            application { routing { availabilityPublicRoutes(cache) } }
            for (badDays in listOf(0, 61, -1, 1000)) {
                val r = client.get("/api/campsite/availability/232447?days=$badDays")
                assertEquals(HttpStatusCode.BadRequest, r.status, "expected 400 for days=$badDays")
                assertTrue(r.bodyAsText().contains("bad_days"))
            }
        }

    @Test
    fun `unknown campground returns 404 when knownIds gate is set`() =
        testApplication {
            val cache = cacheReturning(emptyMap())
            application { routing { availabilityPublicRoutes(cache, knownIds = { setOf("232447") }) } }
            val ok = client.get("/api/campsite/availability/232447")
            assertEquals(HttpStatusCode.OK, ok.status)
            val notFound = client.get("/api/campsite/availability/999999")
            assertEquals(HttpStatusCode.NotFound, notFound.status)
            assertTrue(notFound.bodyAsText().contains("unknown_campground"))
        }

    @Test
    fun `429 from rec_gov maps to 503 rate_limited`() =
        testApplication {
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ -> error("rec.gov 429 after 3 retries") },
                    ttl = Duration.ofMinutes(10),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
            application { routing { availabilityPublicRoutes(cache) } }
            val r = client.get("/api/campsite/availability/232447")
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            val body = parseJson(r.bodyAsText())
            assertEquals("error", body["state"]!!.jsonPrimitive.content)
            assertEquals("rate_limited", body["error"]!!.jsonPrimitive.content)
            assertEquals(60, body["retry_after_s"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun `5xx from rec_gov maps to 503 upstream_5xx`() =
        testApplication {
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ -> error("connection reset") },
                    ttl = Duration.ofMinutes(10),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
            application { routing { availabilityPublicRoutes(cache) } }
            val r = client.get("/api/campsite/availability/232447")
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            assertTrue(r.bodyAsText().contains("upstream_5xx"))
        }

    @Test
    fun `cache hit on second call within TTL`() =
        testApplication {
            val calls = AtomicInteger(0)
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Available")))
                    },
                    ttl = Duration.ofMinutes(10),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
            application { routing { availabilityPublicRoutes(cache) } }
            client.get("/api/campsite/availability/232447?days=1")
            val second = client.get("/api/campsite/availability/232447?days=1")
            val body = parseJson(second.bodyAsText())
            assertEquals(true, body["cache"]!!.jsonObject["hit"]!!.jsonPrimitive.boolean)
            assertEquals(1, calls.get(), "second hit should not refetch")
        }

    @Test
    fun `force=1 bypasses cache`() =
        testApplication {
            val calls = AtomicInteger(0)
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Available")))
                    },
                    ttl = Duration.ofMinutes(10),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
            application { routing { availabilityPublicRoutes(cache) } }
            client.get("/api/campsite/availability/232447?days=1")
            client.get("/api/campsite/availability/232447?days=1&force=1")
            assertEquals(2, calls.get())
        }

    @Test
    fun `per-IP rate limit returns 503 ip_throttled after 10 requests in a minute`() =
        testApplication {
            val cache = cacheReturning(mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Available"))))
            application { routing { availabilityPublicRoutes(cache) } }
            // Burn 10 buckets — all should pass.
            repeat(10) {
                val r = client.get("/api/campsite/availability/232447?days=1")
                assertEquals(HttpStatusCode.OK, r.status, "request ${it + 1} should pass")
            }
            // 11th should throttle.
            val throttled = client.get("/api/campsite/availability/232447?days=1")
            assertEquals(HttpStatusCode.ServiceUnavailable, throttled.status)
            assertTrue(throttled.bodyAsText().contains("ip_throttled"))
        }
}
