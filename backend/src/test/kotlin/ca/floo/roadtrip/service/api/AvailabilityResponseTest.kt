package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.repo.CachedAspiraAvailability
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
                    today = LocalDate.parse("2026-06-10"),
                    days = 1,
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
                    summary = "1 night available",
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
        assertEquals(1, json["window"]!!.jsonObject["days"]!!.jsonPrimitive.int)
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
                    today = LocalDate.parse("2026-07-01"),
                    days = 2,
                    force = false,
                    minNights = 2,
                )

            assertEquals("site:aspira_bc:-2147478966", dto.reservableId)
            assertEquals("available", dto.availability[0].status)
            assertEquals("partial", dto.availability[1].status)
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
                    today = LocalDate.parse("2026-07-01"),
                    days = 1,
                    force = false,
                    minNights = 2,
                    reservableVendor = "aspira_bc",
                )

            assertEquals(1, dto.availability.single().availableCount)
            assertEquals("available", dto.availability.single().status)
            assertEquals(
                listOf("site:aspira_bc:-2147478966"),
                dto.availability.single().availableReservableIds,
            )
        }
}
