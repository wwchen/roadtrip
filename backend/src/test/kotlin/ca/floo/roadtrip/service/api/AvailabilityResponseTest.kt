package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.CampsiteDayObservation
import ca.floo.roadtrip.models.availability.DayClassification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvailabilityResponseTest {
    @Test
    fun `availability renderer serializes stable dto shape`() {
        val body =
            encodeAvailabilityJson(
                availabilityResponseDto(
                    provider = "recgov",
                    startDate = LocalDate.parse("2026-06-10"),
                    endDate = LocalDate.parse("2026-06-11"),
                    perDay =
                        listOf(
                            DayClassification(
                                date = "2026-06-10",
                                status = AvailabilityStatus.AVAILABLE,
                                availableCampsiteIds = listOf(100L, 200L, 300L),
                                campsiteStatuses =
                                    mapOf(
                                        100L to AvailabilityStatus.AVAILABLE,
                                        200L to AvailabilityStatus.AVAILABLE,
                                        300L to AvailabilityStatus.AVAILABLE,
                                        400L to AvailabilityStatus.RESERVED,
                                        500L to AvailabilityStatus.FIRST_COME,
                                    ),
                            ),
                        ),
                    state = "success",
                    seasonBlock = null,
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 600),
                    campgroundId = "232447",
                ),
            )
        val json = Json.parseToJsonElement(body).jsonObject
        val availabilityDay = json["availability"]!!.jsonArray[0].jsonObject

        assertEquals("recgov", json["provider"]!!.jsonPrimitive.content)
        assertEquals("232447", json["campground_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, json["season"])
        assertEquals("2026-06-10", json["start_date"]!!.jsonPrimitive.content)
        assertEquals("2026-06-11", json["end_date"]!!.jsonPrimitive.content)
        assertNull(json["window"])
        assertNull(json["summary"])
        assertEquals("available", availabilityDay["status"]!!.jsonPrimitive.content)
        assertNull(availabilityDay["available_count"])
        assertNull(availabilityDay["total"])
        assertEquals(3, availabilityDay["available_campsite_ids"]!!.jsonArray.size)
        assertEquals(
            "first_come",
            availabilityDay["campsite_statuses"]!!
                .jsonObject["500"]!!
                .jsonPrimitive.content,
        )
        assertEquals(false, json["cache"]!!.jsonObject["hit"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `atomic reservable day observations roll up to stable dto shape`() {
        val olderObservedAt = Instant.parse("2026-06-01T00:00:00Z")
        val observedAt = Instant.parse("2026-06-01T00:05:00Z")
        val dto =
            availabilityResponseFromObservations(
                AvailabilityObservationBatch(
                    provider = "recgov",
                    startDate = LocalDate.parse("2026-06-10"),
                    endDate = LocalDate.parse("2026-06-12"),
                    observations =
                        listOf(
                            CampsiteDayObservation(
                                campsiteId = 100,
                                date = LocalDate.parse("2026-06-10"),
                                observedAt = olderObservedAt,
                                status = AvailabilityStatus.RESERVED,
                            ),
                            CampsiteDayObservation(
                                campsiteId = 100,
                                date = LocalDate.parse("2026-06-10"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.AVAILABLE,
                            ),
                            CampsiteDayObservation(
                                campsiteId = 200,
                                date = LocalDate.parse("2026-06-10"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.RESERVED,
                            ),
                            CampsiteDayObservation(
                                campsiteId = 100,
                                date = LocalDate.parse("2026-06-11"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.RESERVED,
                            ),
                            CampsiteDayObservation(
                                campsiteId = 200,
                                date = LocalDate.parse("2026-06-11"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.UNKNOWN,
                            ),
                        ),
                    cacheBlock = AvailabilityCacheBlock(hit = true, ageSeconds = 3, ttlSeconds = 60),
                    campgroundId = "232447",
                ),
            )

        assertEquals("recgov", dto.provider)
        assertEquals("232447", dto.campgroundId)
        assertEquals("2026-06-10", dto.startDate)
        assertEquals("2026-06-12", dto.endDate)
        assertEquals(AvailabilityStatus.AVAILABLE, dto.availability[0].status)
        assertEquals(listOf(100L), dto.availability[0].availableCampsiteIds)
        assertEquals(AvailabilityStatus.UNKNOWN, dto.availability[1].status)
    }

    @Test
    fun `atomic rollup preserves empty days in the requested window`() {
        val observedAt = Instant.parse("2026-06-01T00:00:00Z")
        val dto =
            availabilityResponseFromObservations(
                AvailabilityObservationBatch(
                    provider = "aspira",
                    startDate = LocalDate.parse("2026-06-10"),
                    endDate = LocalDate.parse("2026-06-12"),
                    observations =
                        listOf(
                            CampsiteDayObservation(
                                campsiteId = 100,
                                date = LocalDate.parse("2026-06-10"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.AVAILABLE,
                            ),
                        ),
                    cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 600),
                    host = "reservation.pc.gc.ca",
                    mapId = "-2147483388",
                ),
            )

        assertEquals(2, dto.availability.size)
        assertEquals(AvailabilityStatus.AVAILABLE, dto.availability[0].status)
        assertEquals(AvailabilityStatus.UNKNOWN, dto.availability[1].status)
        assertEquals(emptyList(), dto.availability[1].availableCampsiteIds)
        assertEquals(emptyMap(), dto.availability[1].campsiteStatuses)
    }

    @Test
    fun `unknown day rollup is not closed for season`() {
        val day =
            dayClassificationFromStatuses(
                date = "2026-06-10",
                statuses = listOf(AvailabilityStatus.UNKNOWN),
            )

        assertEquals(AvailabilityStatus.UNKNOWN, day.status)
        assertEquals("success", classifyWindowState(listOf(day)))
    }

    @Test
    fun `unknown reservable status dominates reserved in day rollup`() {
        val day =
            dayClassificationFromCampsiteStatuses(
                date = "2026-06-10",
                statuses =
                    mapOf(
                        100L to AvailabilityStatus.RESERVED,
                        200L to AvailabilityStatus.UNKNOWN,
                    ),
            )

        assertEquals(AvailabilityStatus.UNKNOWN, day.status)
        assertEquals("success", classifyWindowState(listOf(day)))
    }

    @Test
    fun `reserved and closed known statuses still classify as success`() {
        val days =
            listOf(
                dayClassificationFromStatuses(
                    date = "2026-06-10",
                    statuses = listOf(AvailabilityStatus.RESERVED),
                ),
                dayClassificationFromStatuses(
                    date = "2026-06-11",
                    statuses = listOf(AvailabilityStatus.CLOSED),
                ),
            )

        assertEquals("success", classifyWindowState(days))
    }

    @Test
    fun `availability error renderer returns state error dto shape`() {
        val body = encodeAvailabilityJson(availabilityErrorDto("rate_limited"))
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("error", json["state"]!!.jsonPrimitive.content)
        assertEquals("rate_limited", json["error"]!!.jsonPrimitive.content)
    }
}
