package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.client.AspiraOccupancy
import ca.floo.roadtrip.client.AspiraResourceOccupancy
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.repo.CachedAspiraOccupancy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

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
                                status = "available",
                                availableCount = 3,
                                total = 5,
                                availableReservableIds = listOf("site:recgov:100", "site:recgov:200", "site:recgov:300"),
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
        assertEquals(3, availabilityDay["available_count"]!!.jsonPrimitive.int)
        assertEquals(3, availabilityDay["available_reservable_ids"]!!.jsonArray.size)
        assertEquals(false, json["cache"]!!.jsonObject["hit"]!!.jsonPrimitive.boolean)
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
        assertEquals(300, json["retry_after_s"]!!.jsonPrimitive.int)
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
                fetchAndClassifyAspiraResource(
                    cache = cache,
                    host = "camping.bcparks.ca",
                    mapId = -2147483516,
                    resourceId = "-2147478966",
                    reservableVendor = "aspira_bc",
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-03"),
                    force = false,
                )

            assertEquals("site:aspira_bc:-2147478966", dto.reservableId)
            assertEquals("available", dto.availability[0].status)
            assertEquals("available", dto.availability[1].status)
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
                fetchAndClassifyAspira(
                    cache = cache,
                    host = "camping.bcparks.ca",
                    mapId = -2147483516,
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-02"),
                    force = false,
                    reservableVendor = "aspira_bc",
                )

            assertEquals(2, dto.availability.single().availableCount)
            assertEquals("available", dto.availability.single().status)
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
                fetchAndClassifyAspiraCatalog(
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
                    minNights = 7,
                )

            assertEquals(1, dto.availability[0].availableCount)
            assertEquals(3, dto.availability[0].total)
            assertEquals(listOf("site:aspira_pc:100"), dto.availability[0].availableReservableIds)
            assertEquals("booked", dto.availability[1].status)
            assertEquals(0, dto.availability[1].availableCount)
        }
}
