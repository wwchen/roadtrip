package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.campsite.recgov.booker.poller.Campsite
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the rec.gov classification + render pipeline. The HTTP
 * surface (path validation, rate limiting, dispatch by provider_ref) lives
 * in CampsiteAvailabilityRoutes.kt and is covered by route-level tests
 * against a real Postgres testcontainer.
 *
 * Asserts:
 *   - state classification (success / zero_available / closed_for_season / empty)
 *   - JSON contract shape (provider field, window block, availability array,
 *     cache block)
 *   - cache hit on second call within TTL
 *   - force=true bypasses cache
 *   - upstream errors propagate so the route layer can map to 503
 */
class AvailabilityPublicRoutesTest {
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

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
    private fun futureKey(offsetDays: Long): String = today.plusDays(offsetDays).toString() + "T00:00:00Z"

    private fun cacheReturning(map: Map<String, Campsite>): CachedAvailability =
        CachedAvailability(
            fetchMonth = { _, _ -> map },
            ttl = Duration.ofMinutes(10),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    private fun parseJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun classify(
        cache: CachedAvailability,
        recgovId: String = "232447",
        days: Int = 7,
        force: Boolean = false,
        minNights: Int = 1,
    ): JsonObject {
        // Match the route's rolling-window logic so multi-night classification
        // doesn't truncate at the visible window's edge.
        val end = today.plusDays((days + minNights - 2).toLong())
        val months = monthsCovering(today, end)
        val body =
            encodeAvailabilityJson(
                runBlocking { fetchAndClassifyRecgov(cache, recgovId, today, days, months, force, minNights) },
            )
        return parseJson(body)
    }

    @Test
    fun `success state with availability returns success JSON shape`() {
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
        val body = classify(cacheReturning(map), days = 7)
        assertEquals("success", body["state"]!!.jsonPrimitive.content)
        assertEquals("recgov", body["provider"]!!.jsonPrimitive.content)
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
    fun `zero_available state when all days are booked`() {
        val booked = (0..6L).associate { futureKey(it) to "Reserved" }
        val body = classify(cacheReturning(mapOf("100" to campsiteWith(booked))), days = 7)
        assertEquals("zero_available", body["state"]!!.jsonPrimitive.content)
        assertTrue(body["summary"]!!.jsonPrimitive.content.contains("Fully booked"))
    }

    @Test
    fun `closed_for_season state when all days are Closed`() {
        val closed = (0..6L).associate { futureKey(it) to "Closed" }
        val body = classify(cacheReturning(mapOf("100" to campsiteWith(closed))), days = 7)
        assertEquals("closed_for_season", body["state"]!!.jsonPrimitive.content)
        assertEquals("Closed for season", body["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty state when no campsites returned`() {
        val body = classify(cacheReturning(emptyMap()), days = 7)
        assertEquals("empty", body["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upstream error propagates so route layer can 503`() {
        val cache =
            CachedAvailability(
                fetchMonth = { _, _ -> error("rec.gov 429 after 3 retries") },
                ttl = Duration.ofMinutes(10),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        val ex =
            runCatching {
                classify(cache, days = 1)
            }.exceptionOrNull()
        require(ex != null) { "expected an upstream error to surface" }
        val (status, error) = mapRecgovUpstreamError(ex)
        assertEquals(503, status.value)
        assertEquals("rate_limited", error.error)
    }

    @Test
    fun `5xx maps to upstream_5xx`() {
        val ex = IllegalStateException("connection reset")
        val (_, error) = mapRecgovUpstreamError(ex)
        assertEquals("upstream_5xx", error.error)
    }

    @Test
    fun `cache hit on second call within TTL`() {
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
        classify(cache, days = 1)
        val second = classify(cache, days = 1)
        assertEquals(true, second["cache"]!!.jsonObject["hit"]!!.jsonPrimitive.boolean)
        assertEquals(1, calls.get(), "second hit should not refetch")
    }

    @Test
    fun `force=true bypasses cache`() {
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
        classify(cache, days = 1)
        classify(cache, days = 1, force = true)
        assertEquals(2, calls.get())
    }

    /**
     * minNights=1 should match the legacy single-night classification: a day
     * is "available" iff at least one site has Available status that day,
     * regardless of what the next day looks like.
     */
    @Test
    fun `minNights of 1 collapses to single-night classification`() {
        val map =
            mapOf(
                "100" to
                    campsiteWith(
                        mapOf(
                            futureKey(0) to "Available",
                            futureKey(1) to "Reserved", // booked the next night, irrelevant
                        ),
                    ),
            )
        val body = classify(cacheReturning(map), days = 2, minNights = 1)
        val avail = body["availability"]!!.jsonArray
        assertEquals("available", avail[0].jsonObject["status"]!!.jsonPrimitive.content)
    }

    /**
     * Same data, minNights=2: the same site is open Fri but booked Sat, so it
     * does NOT qualify for a 2-night stay. Day 0 should classify as 'booked'
     * (no site qualifies), not 'available'.
     */
    @Test
    fun `minNights of 2 marks day booked when trailing night is reserved`() {
        val map =
            mapOf(
                "100" to
                    campsiteWith(
                        mapOf(
                            futureKey(0) to "Available",
                            futureKey(1) to "Reserved",
                        ),
                    ),
            )
        val body = classify(cacheReturning(map), days = 1, minNights = 2)
        val avail = body["availability"]!!.jsonArray
        assertEquals("booked", avail[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(
            0,
            avail[0]
                .jsonObject["available_count"]!!
                .jsonPrimitive.content
                .toInt(),
        )
    }

    /**
     * Multi-site park: site A is open Fri+Sat, site B is open Fri but booked
     * Sat. For a 2-night stay starting Fri, only A qualifies, so the day
     * shows as 'partial' with available_count=1, total=2.
     */
    @Test
    fun `minNights of 2 partials when only some sites qualify`() {
        val map =
            mapOf(
                "100" to
                    campsiteWith(
                        mapOf(
                            futureKey(0) to "Available",
                            futureKey(1) to "Available",
                        ),
                    ),
                "200" to
                    campsiteWith(
                        mapOf(
                            futureKey(0) to "Available",
                            futureKey(1) to "Reserved",
                        ),
                    ),
            )
        val body = classify(cacheReturning(map), days = 1, minNights = 2)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("partial", day["status"]!!.jsonPrimitive.content)
        assertEquals(1, day["available_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, day["total"]!!.jsonPrimitive.content.toInt())
    }

    /**
     * 7-night window: a site needs to be open for an entire week. Common case
     * given that weekends fill up first — the site is open Mon-Fri but
     * booked Sat-Sun. No 7-night stay starting Monday is possible.
     */
    @Test
    fun `minNights of 7 rejects sites with weekend gaps`() {
        val byDay =
            (0..6).associate { i ->
                // Booked Sat (day 5) and Sun (day 6); open the rest.
                val s = if (i == 5 || i == 6) "Reserved" else "Available"
                futureKey(i.toLong()) to s
            }
        val map = mapOf("100" to campsiteWith(byDay))
        val body = classify(cacheReturning(map), days = 1, minNights = 7)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("booked", day["status"]!!.jsonPrimitive.content)
    }
}
