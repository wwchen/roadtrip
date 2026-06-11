package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraException
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
}
