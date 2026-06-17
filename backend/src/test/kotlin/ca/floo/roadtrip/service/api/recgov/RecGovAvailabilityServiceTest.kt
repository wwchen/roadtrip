package ca.floo.roadtrip.service.api.recgov

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
 * in AvailabilityRoutes.kt and is covered by route-level tests
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
class RecGovAvailabilityServiceTest {
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
    ): JsonObject {
        val body =
            encodeAvailabilityJson(
                runBlocking { fetchAndClassifyRecgov(cache, recgovId, today, today.plusDays(days.toLong()), force) },
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
        assertEquals(today.toString(), body["window"]!!.jsonObject["start_date"]!!.jsonPrimitive.content)
        assertEquals(today.plusDays(7).toString(), body["window"]!!.jsonObject["end_date"]!!.jsonPrimitive.content)
        assertEquals(7, body["availability"]!!.jsonArray.size)
        assertTrue(body["summary"]!!.jsonPrimitive.content.contains("dates available"))
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

    @Test
    fun `per-day classification ignores following date status`() {
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
        val body = classify(cacheReturning(map), days = 2)
        val avail = body["availability"]!!.jsonArray
        assertEquals("available", avail[0].jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `available day remains available when trailing night is reserved`() {
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
        val body = classify(cacheReturning(map), days = 1)
        val avail = body["availability"]!!.jsonArray
        assertEquals("available", avail[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(
            1,
            avail[0]
                .jsonObject["available_count"]!!
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `per-day availability counts all sites open on that date`() {
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
        val body = classify(cacheReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("available", day["status"]!!.jsonPrimitive.content)
        assertEquals(2, day["available_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, day["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `available reservable ids include all sites available on that date`() {
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
        val body = classify(cacheReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        val ids =
            day["available_reservable_ids"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }

        assertEquals(listOf("site:recgov:100", "site:recgov:200"), ids)
    }

    @Test
    fun `per-day classification does not reject available day for later weekend gaps`() {
        val byDay =
            (0..6).associate { i ->
                // Booked Sat (day 5) and Sun (day 6); open the rest.
                val s = if (i == 5 || i == 6) "Reserved" else "Available"
                futureKey(i.toLong()) to s
            }
        val map = mapOf("100" to campsiteWith(byDay))
        val body = classify(cacheReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("available", day["status"]!!.jsonPrimitive.content)
    }
}
