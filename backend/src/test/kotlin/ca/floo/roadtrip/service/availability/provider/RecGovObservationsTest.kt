package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.recgov.Campsite
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.fixtures.availableCellIds
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.cellStatuses
import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.support.RecGovException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class RecGovObservationsTest {
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    private val provider = RecGovAvailabilityProvider(clientReturning(emptyMap()), enabled = true)

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
        days: Int = 7,
        catalogSiteIds: List<String> = listOf("100"),
    ): JsonObject {
        val p = RecGovAvailabilityProvider(client, enabled = true)
        val campsites =
            catalogSiteIds.map { siteId ->
                campsiteFixture(id = siteId.toLong(), vendor = "recgov", vendorId = siteId)
            }
        val body =
            encodeApiJson(
                responseOf(
                    runBlocking {
                        p.catalogAvailability(testCampground(), campsites, today, today.plusDays(days.toLong()))
                    },
                ),
            )
        return parseJson(body)
    }

    private fun classifyRaw(
        client: RecGovAvailabilityClient,
        days: Int = 7,
    ): JsonObject {
        val p = RecGovAvailabilityProvider(client, enabled = true)
        val body =
            encodeApiJson(
                responseOf(
                    runBlocking { p.availability(testCampground(), today, today.plusDays(days.toLong())) },
                ),
            )
        return parseJson(body)
    }

    private fun testCampground(): Campground =
        Campground(
            id = 1L,
            name = "Test",
            status = null,
            statusDescription = null,
            kind = null,
            shortDescription = null,
            mediumDescription = null,
            longDescription = null,
            location = null,
            maxRvLength = null,
            maxTrailerLength = null,
            hasPullThroughSites = null,
            bigRigFriendly = null,
            reservationUrl = null,
            links = emptyList(),
            photos = emptyList(),
            management = null,
            contact = null,
            connections = JsonNull,
            sourcePayload = JsonNull,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            dataProviderRef = DataProviderRef.RecGov(id = "232447"),
            bookingProvider = "recgov",
            bookingProviderRef = "232447",
        )

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
        assertEquals("232447", body["scope_ref"]!!.jsonPrimitive.content)
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
        val body = classifyRaw(clientReturning(emptyMap()), days = 7)
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
        assertEquals(0, day.availableCellIds().size)
        assertEquals(
            "first_come",
            day.cellStatuses()["100"],
        )
    }

    @Test
    fun `unrecognised upstream status maps to unknown, never reserved`() {
        val map = mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Walk-Up Only")))
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray.single().jsonObject

        assertEquals(
            "unknown",
            day.cellStatuses()["100"],
        )
    }

    @Test
    fun `a window of only reserved and not-available days rolls up to zero_available`() {
        val map =
            mapOf(
                "100" to campsiteWith((0..1L).associate { futureKey(it) to "Reserved" }),
                "200" to campsiteWith((0..1L).associate { futureKey(it) to "Not Available" }),
            )
        val body = classify(clientReturning(map), days = 2, catalogSiteIds = listOf("100", "200"))

        assertEquals("zero_available", body["state"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("reserved", "reserved"),
            body["availability"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `one unmapped status beside a reserved site rolls the day to unknown`() {
        val map =
            mapOf(
                "100" to campsiteWith(mapOf(futureKey(0) to "Reserved")),
                "200" to campsiteWith(mapOf(futureKey(0) to "Walk-Up Only")),
            )
        val body = classify(clientReturning(map), days = 1, catalogSiteIds = listOf("100", "200"))
        val day = body["availability"]!!.jsonArray.single().jsonObject

        assertEquals("success", body["state"]!!.jsonPrimitive.content)
        assertEquals("unknown", day["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `not available maps to reserved`() {
        val map = mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Not Available")))
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray.single().jsonObject

        assertEquals(
            "reserved",
            day.cellStatuses()["100"],
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
            day.cellStatuses()["100"],
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
            day.cellStatuses()["100"],
        )
    }

    @Test
    fun `catalog availability keeps requested sites omitted by upstream as unknown`() {
        val campsites =
            listOf(
                campsiteFixture(id = 100L, vendor = "recgov", vendorId = "100"),
                campsiteFixture(id = 200L, vendor = "recgov", vendorId = "200"),
            )
        val p = RecGovAvailabilityProvider(clientReturning(emptyMap()), enabled = true)
        val body =
            encodeApiJson(
                responseOf(
                    runBlocking {
                        p.catalogAvailability(testCampground(), campsites, today, today.plusDays(1))
                    },
                ),
            )
        val day = parseJson(body)["availability"]!!.jsonArray.single().jsonObject

        assertEquals("unknown", day["status"]!!.jsonPrimitive.content)
        assertEquals(0, day.availableCellIds().size)
        assertEquals(2, day.cellStatuses().size)
        assertEquals(
            "unknown",
            day.cellStatuses()["100"],
        )
        assertEquals(
            "unknown",
            day.cellStatuses()["200"],
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
        require(ex is AvailabilityProviderError.RateLimited) { "expected RateLimited, got $ex" }
        assertEquals("rate_limited", ex.code)
    }

    @Test
    fun `a typed client 429 maps to the rate-limited outcome`() {
        val client =
            object : RecGovAvailabilityClient {
                override suspend fun fetchMonth(
                    campgroundId: String,
                    monthStart: String,
                ): Map<String, Campsite> = throw RecGovException("rec.gov 429 rate limit on 1/2026-12", httpStatus = 429)
            }
        val ex = runCatching { classify(client, days = 1) }.exceptionOrNull()
        require(ex is AvailabilityProviderError.RateLimited) { "expected RateLimited, got $ex" }
        assertEquals("rate_limited", ex.code)
    }

    @Test
    fun `5xx maps to upstream_5xx`() {
        val classified = upstreamAvailabilityError(cause = IllegalStateException("connection reset"), httpStatus = null)

        assertEquals("upstream_5xx", classified.code)
    }

    @Test
    fun `a connect failure classifies as unreachable, not a vendor 5xx`() {
        // The incident's misdiagnosis, reproduced for rec.gov: a transport
        // failure that never reached the vendor must not read as
        // "booking site returned an error". runWithErrorMapping now routes it
        // through the shared classifier, so a ConnectException in the chain
        // becomes UpstreamUnreachable.
        val connect = java.net.ConnectException("Connection refused")
        val client =
            object : RecGovAvailabilityClient {
                override suspend fun fetchMonth(
                    campgroundId: String,
                    monthStart: String,
                ): Map<String, Campsite> = throw RuntimeException("rec.gov fetch failed", connect)
            }
        val ex =
            runCatching { classify(client, days = 1) }.exceptionOrNull()
        require(ex is AvailabilityProviderError.UpstreamUnreachable) { "expected UpstreamUnreachable, got $ex" }
    }

    @Test
    fun `per-day classification ignores following date status`() {
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
        assertEquals(listOf("100"), avail[0].jsonObject.availableCellIds())
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
        val body = classify(clientReturning(map), days = 1, catalogSiteIds = listOf("100", "200"))
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("available", day["status"]!!.jsonPrimitive.content)
        assertEquals(2, day.availableCellIds().size)
        assertEquals(2, day.cellStatuses().size)
    }

    @Test
    fun `available campsite ids include all sites available on that date`() {
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
        val body = classify(clientReturning(map), days = 1, catalogSiteIds = listOf("100", "200"))
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("100", "200"), day.availableCellIds())
    }

    @Test
    fun `per-day classification does not reject available day for later weekend gaps`() {
        val byDay =
            (0..6).associate { i ->
                val s = if (i == 5 || i == 6) "Reserved" else "Available"
                futureKey(i.toLong()) to s
            }
        val map = mapOf("100" to campsiteWith(byDay))
        val body = classify(clientReturning(map), days = 1)
        val day = body["availability"]!!.jsonArray[0].jsonObject
        assertEquals("available", day["status"]!!.jsonPrimitive.content)
    }
}

/**
 * These tests read parsed statuses, not watchability, so every window is quoted
 * from its own start date by a provider the poller can reach.
 */
private fun responseOf(batch: AvailabilityObservationBatch): AvailabilityResponseDto =
    availabilityResponseFromObservations(batch, pollingSupported = true, earliestDate = batch.startDate)
