package ca.floo.roadtrip.service.reservation.adapters.recgov

import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the rec.gov classification + render pipeline. The HTTP
 * surface (path validation, rate limiting, dispatch by provider_ref) lives
 * in AvailabilityRoutes.kt and is covered by route-level tests
 * against a real Postgres testcontainer.
 *
 * Asserts:
 *   - state classification (success / zero_available / closed_for_season / empty)
 *   - JSON contract shape (provider field, top-level date window, availability array)
 *   - upstream errors propagate so the route layer can map to 503
 */
class RecGovObservationsTest {
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

    private fun clientReturning(map: Map<String, Campsite>): RecGovAvailabilityClient =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, Campsite> = map
        }

    private fun parseJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun classify(
        client: RecGovAvailabilityClient,
        recgovId: String = "232447",
        days: Int = 7,
    ): JsonObject {
        val body =
            encodeAvailabilityJson(
                availabilityResponseFromObservations(
                    runBlocking { fetchRecgovAvailabilityObservations(client, recgovId, today, today.plusDays(days.toLong())) },
                ),
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
        val body = classify(clientReturning(map), days = 7)
        assertEquals("success", body["state"]!!.jsonPrimitive.content)
        assertEquals("recgov", body["provider"]!!.jsonPrimitive.content)
        assertEquals("232447", body["campground_id"]!!.jsonPrimitive.content)
        assertEquals(today.toString(), body["start_date"]!!.jsonPrimitive.content)
        assertEquals(today.plusDays(7).toString(), body["end_date"]!!.jsonPrimitive.content)
        assertEquals(7, body["availability"]!!.jsonArray.size)
    }

    @Test
    fun `zero_available state when all days are booked`() {
        val booked = (0..6L).associate { futureKey(it) to "Reserved" }
        val body = classify(clientReturning(mapOf("100" to campsiteWith(booked))), days = 7)
        assertEquals("zero_available", body["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `closed_for_season state when all days are Closed`() {
        val closed = (0..6L).associate { futureKey(it) to "Closed" }
        val body = classify(clientReturning(mapOf("100" to campsiteWith(closed))), days = 7)
        assertEquals("closed_for_season", body["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty state when no campsites returned`() {
        val body = classify(clientReturning(emptyMap()), days = 7)
        assertEquals("empty", body["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `not reservable maps to first come with reservable statuses`() {
        val map =
            mapOf(
                "100" to
                    campsiteWith(
                        mapOf(futureKey(0) to "Not Reservable"),
                    ),
            )
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray.single().jsonObject

        assertEquals("success", body["state"]!!.jsonPrimitive.content)
        assertEquals("first_come", day["status"]!!.jsonPrimitive.content)
        assertEquals(0, day["available_reservable_ids"]!!.jsonArray.size)
        assertEquals(
            "first_come",
            day["reservable_statuses"]!!
                .jsonObject["site:recgov:100"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `missing date row maps to unknown rather than closed`() {
        val map = mapOf("100" to campsiteWith(emptyMap()))
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray.single().jsonObject

        assertEquals("success", body["state"]!!.jsonPrimitive.content)
        assertEquals("unknown", day["status"]!!.jsonPrimitive.content)
        assertEquals(
            "unknown",
            day["reservable_statuses"]!!
                .jsonObject["site:recgov:100"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `null availability artifact maps to unknown rather than reserved`() {
        val map = mapOf("100" to campsiteWith(mapOf(futureKey(0) to "null")))
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray.single().jsonObject

        assertEquals("success", body["state"]!!.jsonPrimitive.content)
        assertEquals("unknown", day["status"]!!.jsonPrimitive.content)
        assertEquals(
            "unknown",
            day["reservable_statuses"]!!
                .jsonObject["site:recgov:100"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `catalog availability keeps requested sites omitted by upstream as unknown`() {
        val body =
            encodeAvailabilityJson(
                availabilityResponseFromObservations(
                    runBlocking {
                        fetchRecgovCatalogObservations(
                            client = clientReturning(emptyMap()),
                            recgovId = "232447",
                            catalogIdsByCampsiteId = mapOf("100" to 100L, "200" to 200L),
                            startDate = today,
                            endDate = today.plusDays(1),
                        )
                    },
                ),
            )
        val day = parseJson(body)["availability"]!!.jsonArray.single().jsonObject

        assertEquals("unknown", day["status"]!!.jsonPrimitive.content)
        assertEquals(0, day["available_reservable_ids"]!!.jsonArray.size)
        assertEquals(2, day["reservable_statuses"]!!.jsonObject.size)
        assertEquals(
            "unknown",
            day["reservable_statuses"]!!
                .jsonObject["100"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "unknown",
            day["reservable_statuses"]!!
                .jsonObject["200"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `reservable availability keeps requested site omitted by upstream as unknown`() {
        val body =
            encodeAvailabilityJson(
                availabilityResponseFromObservations(
                    runBlocking {
                        fetchRecgovReservableObservations(
                            client = clientReturning(emptyMap()),
                            recgovId = "232447",
                            campsiteId = "100",
                            startDate = today,
                            endDate = today.plusDays(1),
                        )
                    },
                ),
            )
        val json = parseJson(body)
        val day = json["availability"]!!.jsonArray.single().jsonObject

        assertEquals("site:recgov:100", json["reservable_id"]!!.jsonPrimitive.content)
        assertEquals("unknown", day["status"]!!.jsonPrimitive.content)
        assertEquals(0, day["available_reservable_ids"]!!.jsonArray.size)
        assertEquals(1, day["reservable_statuses"]!!.jsonObject.size)
        assertEquals(
            "unknown",
            day["reservable_statuses"]!!
                .jsonObject["site:recgov:100"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `upstream error propagates so route layer can 503`() {
        val client =
            object : RecGovAvailabilityClient {
                override suspend fun fetchMonth(
                    campgroundId: String,
                    monthStart: String,
                ): Map<String, Campsite> = error("rec.gov 429 after 3 retries")
            }
        val ex =
            runCatching {
                classify(client, days = 1)
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
        val body = classify(clientReturning(map), days = 2)
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
        val body = classify(clientReturning(map), days = 1)
        val avail = body["availability"]!!.jsonArray
        assertEquals("available", avail[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("site:recgov:100"),
            avail[0]
                .jsonObject["available_reservable_ids"]!!
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `per-day availability includes all sites open on that date`() {
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
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("available", day["status"]!!.jsonPrimitive.content)
        assertEquals(2, day["available_reservable_ids"]!!.jsonArray.size)
        assertEquals(2, day["reservable_statuses"]!!.jsonObject.size)
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
        val body = classify(clientReturning(map), days = 1)
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
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("available", day["status"]!!.jsonPrimitive.content)
    }
}
