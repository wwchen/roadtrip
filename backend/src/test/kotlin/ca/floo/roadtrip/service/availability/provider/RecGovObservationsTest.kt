package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.recgov.Campsite
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.fixtures.availableIdsOn
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.rollupOn
import ca.floo.roadtrip.fixtures.statusesOn
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.service.api.campsiteWindowStatuses
import ca.floo.roadtrip.service.api.isClosedForSeason
import ca.floo.roadtrip.support.RecGovException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun classify(
        client: RecGovAvailabilityClient,
        days: Int = 7,
        catalogSiteIds: List<String> = listOf("100"),
    ): AvailabilityObservationBatch {
        val p = RecGovAvailabilityProvider(client, enabled = true)
        val campsites =
            catalogSiteIds.map { siteId ->
                campsiteFixture(id = siteId.toLong(), vendor = "recgov", vendorId = siteId)
            }
        return runBlocking {
            p.catalogAvailability(testCampground(), campsites, today, today.plusDays(days.toLong()))
        }
    }

    private fun classifyRaw(
        client: RecGovAvailabilityClient,
        days: Int = 7,
    ): AvailabilityObservationBatch =
        runBlocking {
            RecGovAvailabilityProvider(client, enabled = true)
                .availability(testCampground(), today, today.plusDays(days.toLong()))
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
    fun `a parsed window carries the provider, scope and every date asked for`() {
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
        val batch = classify(clientReturning(map), days = 7)

        assertEquals("recgov", batch.provider)
        assertEquals("232447", batch.scope!!.serialize())
        assertEquals(today, batch.startDate)
        assertEquals(today.plusDays(7), batch.endDate)
        // A date upstream said nothing about is still a date the window answers.
        assertEquals(
            listOf(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.AVAILABLE,
            ) + List(4) { AvailabilityStatus.UNKNOWN },
            (0 until 7).map { batch.rollupOn(it) },
        )
    }

    @Test
    fun `a fully booked window parses to reserved with nothing bookable`() {
        val booked = (0..6L).associate { futureKey(it) to "Reserved" }
        val batch = classify(clientReturning(mapOf("100" to campsiteWith(booked))), days = 7)

        assertEquals(List(7) { AvailabilityStatus.RESERVED }, (0 until 7).map { batch.rollupOn(it) })
        assertTrue((0 until 7).all { batch.availableIdsOn(it).isEmpty() })
    }

    @Test
    fun `a window of only Closed days closes the stream for the season`() {
        val closed = (0..6L).associate { futureKey(it) to "Closed" }
        val batch = classify(clientReturning(mapOf("100" to campsiteWith(closed))), days = 7)

        assertTrue(isClosedForSeason(campsiteWindowStatuses(batch.startDate, batch.endDate, batch.observations)))
    }

    @Test
    fun `a campground upstream returns no campsites for observes nothing`() {
        val batch = classifyRaw(clientReturning(emptyMap()), days = 7)

        assertEquals(emptyList(), batch.observations)
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
        val batch = classify(clientReturning(map), days = 1)

        assertEquals(AvailabilityStatus.FIRST_COME, batch.rollupOn(0))
        // Walk-up is not a booking anyone can make, so it is not bookable online.
        assertEquals(emptyList(), batch.availableIdsOn(0))
        assertEquals(mapOf(100L to AvailabilityStatus.FIRST_COME), batch.statusesOn(0))
    }

    @Test
    fun `unrecognised upstream status maps to unknown, never reserved`() {
        val map = mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Walk-Up Only")))
        val batch = classify(clientReturning(map), days = 1)

        assertEquals(mapOf(100L to AvailabilityStatus.UNKNOWN), batch.statusesOn(0))
    }

    @Test
    fun `reserved and not-available both parse to reserved`() {
        val map =
            mapOf(
                "100" to campsiteWith((0..1L).associate { futureKey(it) to "Reserved" }),
                "200" to campsiteWith((0..1L).associate { futureKey(it) to "Not Available" }),
            )
        val batch = classify(clientReturning(map), days = 2, catalogSiteIds = listOf("100", "200"))

        assertEquals(
            listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.RESERVED),
            listOf(batch.rollupOn(0), batch.rollupOn(1)),
        )
        assertEquals(
            mapOf(100L to AvailabilityStatus.RESERVED, 200L to AvailabilityStatus.RESERVED),
            batch.statusesOn(0),
        )
    }

    @Test
    fun `one unmapped status beside a reserved site rolls the day to unknown`() {
        val map =
            mapOf(
                "100" to campsiteWith(mapOf(futureKey(0) to "Reserved")),
                "200" to campsiteWith(mapOf(futureKey(0) to "Walk-Up Only")),
            )
        val batch = classify(clientReturning(map), days = 1, catalogSiteIds = listOf("100", "200"))

        assertEquals(AvailabilityStatus.UNKNOWN, batch.rollupOn(0))
    }

    @Test
    fun `not available maps to reserved`() {
        val map = mapOf("100" to campsiteWith(mapOf(futureKey(0) to "Not Available")))
        val batch = classify(clientReturning(map), days = 1)

        assertEquals(mapOf(100L to AvailabilityStatus.RESERVED), batch.statusesOn(0))
    }

    @Test
    fun `missing date row maps to unknown rather than closed`() {
        val map = mapOf("100" to campsiteWith(emptyMap()))
        val batch = classify(clientReturning(map), days = 1)

        assertEquals(AvailabilityStatus.UNKNOWN, batch.rollupOn(0))
        assertEquals(mapOf(100L to AvailabilityStatus.UNKNOWN), batch.statusesOn(0))
    }

    @Test
    fun `null availability artifact maps to unknown rather than reserved`() {
        val map = mapOf("100" to campsiteWith(mapOf(futureKey(0) to "null")))
        val batch = classify(clientReturning(map), days = 1)

        assertEquals(AvailabilityStatus.UNKNOWN, batch.rollupOn(0))
        assertEquals(mapOf(100L to AvailabilityStatus.UNKNOWN), batch.statusesOn(0))
    }

    @Test
    fun `catalog availability keeps requested sites omitted by upstream as unknown`() {
        val campsites =
            listOf(
                campsiteFixture(id = 100L, vendor = "recgov", vendorId = "100"),
                campsiteFixture(id = 200L, vendor = "recgov", vendorId = "200"),
            )
        val p = RecGovAvailabilityProvider(clientReturning(emptyMap()), enabled = true)
        val batch =
            runBlocking {
                p.catalogAvailability(testCampground(), campsites, today, today.plusDays(1))
            }

        assertEquals(AvailabilityStatus.UNKNOWN, batch.rollupOn(0))
        assertEquals(emptyList(), batch.availableIdsOn(0))
        assertEquals(
            mapOf(100L to AvailabilityStatus.UNKNOWN, 200L to AvailabilityStatus.UNKNOWN),
            batch.statusesOn(0),
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
        val batch = classify(clientReturning(map), days = 2)

        assertEquals(AvailabilityStatus.AVAILABLE, batch.rollupOn(0))
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
        val batch = classify(clientReturning(map), days = 1)

        assertEquals(AvailabilityStatus.AVAILABLE, batch.rollupOn(0))
        assertEquals(listOf(100L), batch.availableIdsOn(0))
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
        val batch = classify(clientReturning(map), days = 1, catalogSiteIds = listOf("100", "200"))

        assertEquals(AvailabilityStatus.AVAILABLE, batch.rollupOn(0))
        assertEquals(2, batch.availableIdsOn(0).size)
        assertEquals(2, batch.statusesOn(0).size)
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
        val batch = classify(clientReturning(map), days = 1, catalogSiteIds = listOf("100", "200"))

        assertEquals(listOf(100L, 200L), batch.availableIdsOn(0))
    }

    @Test
    fun `per-day classification does not reject available day for later weekend gaps`() {
        val byDay =
            (0..6).associate { i ->
                val s = if (i == 5 || i == 6) "Reserved" else "Available"
                futureKey(i.toLong()) to s
            }
        val map = mapOf("100" to campsiteWith(byDay))
        val batch = classify(clientReturning(map), days = 1)

        assertEquals(AvailabilityStatus.AVAILABLE, batch.rollupOn(0))
    }
}
