package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.clients.aspira.AspiraOccupancy
import ca.floo.roadtrip.clients.aspira.AspiraResourceOccupancy
import ca.floo.roadtrip.clients.cache.CachedAspiraAvailability
import ca.floo.roadtrip.clients.cache.CachedAspiraOccupancy
import ca.floo.roadtrip.service.api.aspira.AspiraCatalogReservable
import ca.floo.roadtrip.service.api.aspira.fetchAndClassifyAspiraCatalogOccupancy
import ca.floo.roadtrip.service.api.aspira.fetchAspiraAvailabilityObservations
import ca.floo.roadtrip.service.api.aspira.fetchAspiraCatalogObservations
import ca.floo.roadtrip.service.api.aspira.fetchAspiraResourceObservations
import ca.floo.roadtrip.service.api.aspira.mapAspiraUpstreamError
import ca.floo.roadtrip.service.api.aspira.requireAspiraAllowedHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
                                availableCount = 3,
                                total = 5,
                                availableReservableIds = listOf("site:recgov:100", "site:recgov:200", "site:recgov:300"),
                                reservableStatuses =
                                    mapOf(
                                        "site:recgov:100" to AvailabilityStatus.AVAILABLE,
                                        "site:recgov:200" to AvailabilityStatus.AVAILABLE,
                                        "site:recgov:300" to AvailabilityStatus.AVAILABLE,
                                        "site:recgov:400" to AvailabilityStatus.RESERVED,
                                        "site:recgov:500" to AvailabilityStatus.FIRST_COME,
                                    ),
                            ),
                        ),
                    state = "success",
                    summary = "1 date available",
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
        assertEquals("2026-06-10", json["window"]!!.jsonObject["start_date"]!!.jsonPrimitive.content)
        assertEquals("2026-06-11", json["window"]!!.jsonObject["end_date"]!!.jsonPrimitive.content)
        assertEquals("available", availabilityDay["status"]!!.jsonPrimitive.content)
        assertEquals(3, availabilityDay["available_count"]!!.jsonPrimitive.int)
        assertEquals(3, availabilityDay["available_reservable_ids"]!!.jsonArray.size)
        assertEquals(
            "first_come",
            availabilityDay["reservable_statuses"]!!
                .jsonObject["site:recgov:500"]!!
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
                            ReservableDayObservation(
                                reservableId = "site:recgov:100",
                                date = LocalDate.parse("2026-06-10"),
                                observedAt = olderObservedAt,
                                status = AvailabilityStatus.RESERVED,
                            ),
                            ReservableDayObservation(
                                reservableId = "site:recgov:100",
                                date = LocalDate.parse("2026-06-10"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.AVAILABLE,
                            ),
                            ReservableDayObservation(
                                reservableId = "site:recgov:200",
                                date = LocalDate.parse("2026-06-10"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.RESERVED,
                            ),
                            ReservableDayObservation(
                                reservableId = "site:recgov:100",
                                date = LocalDate.parse("2026-06-11"),
                                observedAt = observedAt,
                                status = AvailabilityStatus.RESERVED,
                            ),
                            ReservableDayObservation(
                                reservableId = "site:recgov:200",
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
        assertEquals("2026-06-10", dto.window.startDate)
        assertEquals("2026-06-12", dto.window.endDate)
        assertEquals("1 date available", dto.summary)
        assertEquals(AvailabilityStatus.AVAILABLE, dto.availability[0].status)
        assertEquals(1, dto.availability[0].availableCount)
        assertEquals(2, dto.availability[0].total)
        assertEquals(listOf("site:recgov:100"), dto.availability[0].availableReservableIds)
        assertEquals(AvailabilityStatus.UNKNOWN, dto.availability[1].status)
        assertEquals(0, dto.availability[1].availableCount)
        assertEquals(2, dto.availability[1].total)
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
                            ReservableDayObservation(
                                reservableId = "site:aspira_pc:100",
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
        assertEquals(0, dto.availability[1].availableCount)
        assertEquals(0, dto.availability[1].total)
        assertEquals(emptyList(), dto.availability[1].availableReservableIds)
        assertEquals(emptyMap(), dto.availability[1].reservableStatuses)
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
        assertEquals("Availability unknown", summarizeWindow(1, listOf(day), "success"))
    }

    @Test
    fun `unknown reservable status dominates reserved in day rollup`() {
        val day =
            dayClassificationFromReservableStatuses(
                date = "2026-06-10",
                statuses =
                    mapOf(
                        "site:recgov:100" to AvailabilityStatus.RESERVED,
                        "site:recgov:200" to AvailabilityStatus.UNKNOWN,
                    ),
            )

        assertEquals(AvailabilityStatus.UNKNOWN, day.status)
        assertEquals("success", classifyWindowState(listOf(day)))
        assertEquals("Availability unknown", summarizeWindow(1, listOf(day), "success"))
    }

    @Test
    fun `summary has reserved fallback for non-bookable known statuses`() {
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
        assertEquals("Reserved next 2 days", summarizeWindow(2, days, "success"))
    }

    @Test
    fun `availability error renderer returns state error dto shape`() {
        val body = encodeAvailabilityJson(availabilityErrorDto("rate_limited", retryAfterS = 60))
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("error", json["state"]!!.jsonPrimitive.content)
        assertEquals("rate_limited", json["error"]!!.jsonPrimitive.content)
        assertEquals(60, json["retry_after_s"]!!.jsonPrimitive.int)
    }

    @Test
    fun `aspira upstream mapper uses availability error dto renderer`() {
        val (status, error) = mapAspiraUpstreamError(AspiraException("WAF challenge", httpStatus = 503))
        val json = Json.parseToJsonElement(encodeAvailabilityJson(error)).jsonObject

        assertEquals(503, status.value)
        assertEquals("error", json["state"]!!.jsonPrimitive.content)
        assertEquals("upstream_blocked", json["error"]!!.jsonPrimitive.content)
        assertEquals(60, json["retry_after_s"]!!.jsonPrimitive.int)
        assertEquals(503, json["upstream_status"]!!.jsonPrimitive.int)
    }

    @Test
    fun `aspira upstream mapper treats 403 as upstream blocked`() {
        val (status, error) = mapAspiraUpstreamError(AspiraException("Forbidden", httpStatus = 403))

        assertEquals(503, status.value)
        assertEquals("upstream_blocked", error.error)
        assertEquals(403, error.upstream_status)
    }

    @Test
    fun `aspira host allowlist rejects unknown hosts`() {
        assertEquals("reservation.pc.gc.ca", requireAspiraAllowedHost(" RESERVATION.PC.GC.CA "))
        assertFailsWith<IllegalArgumentException> {
            requireAspiraAllowedHost("example.test")
        }
    }

    @Test
    fun `aspira resource availability narrows cached map response to one resource`() =
        runBlocking {
            val cache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource = mapOf("-2147478966" to listOf(1, 1, 5)),
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraResourceObservations(
                        cache = cache,
                        host = "camping.bcparks.ca",
                        mapId = -2147483516,
                        resourceId = "-2147478966",
                        reservableVendor = "aspira_bc",
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                        force = false,
                    ),
                )

            assertEquals("site:aspira_bc:-2147478966", dto.reservableId)
            assertEquals(AvailabilityStatus.AVAILABLE, dto.availability[0].status)
            assertEquals(AvailabilityStatus.AVAILABLE, dto.availability[1].status)
        }

    @Test
    fun `aspira campground availability emits available resource ids when resources are present`() =
        runBlocking {
            val cache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource =
                                mapOf(
                                    "-2147478966" to listOf(1, 1),
                                    "-2147478967" to listOf(1, 5),
                                ),
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraAvailabilityObservations(
                        cache = cache,
                        host = "camping.bcparks.ca",
                        mapId = -2147483516,
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                        force = false,
                        reservableVendor = "aspira_bc",
                    ),
                )

            assertEquals(2, dto.availability.single().availableCount)
            assertEquals(AvailabilityStatus.AVAILABLE, dto.availability.single().status)
            assertEquals(
                listOf("site:aspira_bc:-2147478966", "site:aspira_bc:-2147478967"),
                dto.availability.single().availableReservableIds,
            )
        }

    @Test
    fun `aspira catalog availability aggregates linked resources across child maps`() =
        runBlocking {
            val cache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        when (mapId) {
                            -101 ->
                                AspiraAvailability(
                                    mapId = mapId,
                                    parkRollup = emptyList(),
                                    byMapLink = emptyMap(),
                                    byResource =
                                        mapOf(
                                            "a" to listOf(1, 1),
                                            "b" to listOf(5, 1),
                                        ),
                                )
                            -202 ->
                                AspiraAvailability(
                                    mapId = mapId,
                                    parkRollup = emptyList(),
                                    byMapLink = emptyMap(),
                                    byResource = mapOf("c" to listOf(1, 5)),
                                )
                            else ->
                                AspiraAvailability(
                                    mapId = mapId,
                                    parkRollup = emptyList(),
                                    byMapLink = emptyMap(),
                                    byResource = emptyMap(),
                                )
                        }
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraCatalogObservations(
                        cache = cache,
                        host = "washington.goingtocamp.com",
                        parentMapId = -999,
                        reservables =
                            listOf(
                                AspiraCatalogReservable("site:aspira_wa:a", "a", -101),
                                AspiraCatalogReservable("site:aspira_wa:b", "b", -101),
                                AspiraCatalogReservable("site:aspira_wa:c", "c", -202),
                                AspiraCatalogReservable("site:aspira_wa:missing", "missing", -202),
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                        force = false,
                    ),
                )

            assertEquals((-999).toString(), dto.mapId)
            assertEquals(2, dto.availability[0].availableCount)
            assertEquals(4, dto.availability[0].total)
            assertEquals(
                listOf("site:aspira_wa:a", "site:aspira_wa:c"),
                dto.availability[0].availableReservableIds,
            )
            assertEquals(2, dto.availability[1].availableCount)
            assertEquals(4, dto.availability[1].total)
        }

    @Test
    fun `aspira catalog missing resource day is unknown with reservable status`() =
        runBlocking {
            val cache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource = emptyMap(),
                        )
                    },
                )

            val dto =
                availabilityResponseFromObservations(
                    fetchAspiraCatalogObservations(
                        cache = cache,
                        host = "reservation.pc.gc.ca",
                        parentMapId = -999,
                        reservables =
                            listOf(
                                AspiraCatalogReservable("site:aspira_pc:100", "100", -101),
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                        force = false,
                    ),
                )

            assertEquals(AvailabilityStatus.UNKNOWN, dto.availability.single().status)
            assertEquals(0, dto.availability.single().availableCount)
            assertEquals(1, dto.availability.single().total)
            assertEquals(
                mapOf("site:aspira_pc:100" to AvailabilityStatus.UNKNOWN),
                dto.availability.single().reservableStatuses,
            )
        }

    @Test
    fun `aspira catalog availability uses occupancy search availability`() =
        runBlocking {
            val cache =
                CachedAspiraOccupancy(
                    fetcher = { _, resourceLocationId, start, _ ->
                        val rows =
                            when (start) {
                                LocalDate.parse("2026-06-17") ->
                                    listOf(
                                        AspiraResourceOccupancy(resourceId = 100, availability = 0),
                                        AspiraResourceOccupancy(resourceId = 200, availability = 2),
                                        AspiraResourceOccupancy(resourceId = 300, availability = 0, filtered = true),
                                    )
                                else ->
                                    listOf(
                                        AspiraResourceOccupancy(resourceId = 100, availability = 2),
                                        AspiraResourceOccupancy(resourceId = 200, availability = 2),
                                        AspiraResourceOccupancy(resourceId = 300, availability = 2),
                                    )
                            }
                        AspiraOccupancy(
                            resourceLocationId = resourceLocationId,
                            resourceOccupancy = rows,
                        )
                    },
                )

            val dto =
                fetchAndClassifyAspiraCatalogOccupancy(
                    cache = cache,
                    host = "reservation.pc.gc.ca",
                    parentMapId = -999,
                    resourceLocationId = -123,
                    reservables =
                        listOf(
                            AspiraCatalogReservable("site:aspira_pc:100", "100", -101),
                            AspiraCatalogReservable("site:aspira_pc:200", "200", -101),
                            AspiraCatalogReservable("site:aspira_pc:300", "300", -101),
                        ),
                    today = LocalDate.parse("2026-06-17"),
                    days = 2,
                    force = false,
                )

            assertEquals(1, dto.availability[0].availableCount)
            assertEquals(3, dto.availability[0].total)
            assertEquals(listOf("site:aspira_pc:100"), dto.availability[0].availableReservableIds)
            assertEquals(AvailabilityStatus.RESERVED, dto.availability[1].status)
            assertEquals(0, dto.availability[1].availableCount)
        }
}
