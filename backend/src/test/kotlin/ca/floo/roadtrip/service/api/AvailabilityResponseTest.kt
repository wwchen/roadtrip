package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.route.common.encodeApiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val windowStart: LocalDate = LocalDate.parse("2026-06-10")

class AvailabilityResponseTest {
    @Test
    fun `the latest observation for a date wins`() {
        val statuses =
            campsiteWindowStatuses(
                startDate = windowStart,
                endDate = windowStart.plusDays(1),
                observations =
                    listOf(
                        observation(windowStart, "2026-06-01T00:00:00Z", AvailabilityStatus.RESERVED),
                        observation(windowStart, "2026-06-01T00:05:00Z", AvailabilityStatus.AVAILABLE),
                    ),
            )

        assertEquals(listOf(AvailabilityStatus.AVAILABLE), statuses)
    }

    @Test
    fun `a window keeps every date asked for, observed or not`() {
        val statuses =
            campsiteWindowStatuses(
                startDate = windowStart,
                endDate = windowStart.plusDays(3),
                observations =
                    listOf(
                        observation(windowStart, "2026-06-01T00:00:00Z", AvailabilityStatus.AVAILABLE),
                        // Outside the window: it dates nothing the window answers.
                        observation(windowStart.plusDays(5), "2026-06-01T00:00:00Z", AvailabilityStatus.RESERVED),
                    ),
            )

        assertEquals(
            listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.UNKNOWN, AvailabilityStatus.UNKNOWN),
            statuses,
        )
    }

    @Test
    fun `an unobserved night is not a closed one`() {
        assertFalse(isClosedForSeason(listOf(AvailabilityStatus.UNKNOWN)))
        assertFalse(isClosedForSeason(listOf(AvailabilityStatus.CLOSED, AvailabilityStatus.UNKNOWN)))
    }

    @Test
    fun `a window nobody answered is not closed for the season`() {
        assertFalse(isClosedForSeason(emptyList()))
    }

    @Test
    fun `reserved and closed nights together are not a closed season`() {
        assertFalse(isClosedForSeason(listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.CLOSED)))
        assertTrue(isClosedForSeason(listOf(AvailabilityStatus.CLOSED, AvailabilityStatus.CLOSED)))
    }

    @Test
    fun `availability error renderer returns state error dto shape`() {
        val body = encodeApiJson(availabilityErrorDto("rate_limited"))
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("error", json["state"]!!.jsonPrimitive.content)
        assertEquals("rate_limited", json["error"]!!.jsonPrimitive.content)
    }
}

private fun observation(
    date: LocalDate,
    observedAt: String,
    status: AvailabilityStatus,
): CampsiteDayObservation =
    CampsiteDayObservation(
        campsiteId = 100L,
        date = date,
        observedAt = Instant.parse(observedAt),
        status = status,
    )
