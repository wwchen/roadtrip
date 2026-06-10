package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AvailabilityResponseTest {
    @Test
    fun `availability error renderer returns state error dto shape`() {
        val body = renderAvailabilityErrorJson("rate_limited", retryAfterS = 60)
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("error", json["state"]!!.jsonPrimitive.content)
        assertEquals("rate_limited", json["error"]!!.jsonPrimitive.content)
        assertEquals(60, json["retry_after_s"]!!.jsonPrimitive.int)
    }

    @Test
    fun `aspira upstream mapper uses availability error dto renderer`() {
        val (status, body) = mapAspiraUpstreamError(AspiraException("WAF challenge", httpStatus = 503))
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(503, status.value)
        assertEquals("error", json["state"]!!.jsonPrimitive.content)
        assertEquals("upstream_blocked", json["error"]!!.jsonPrimitive.content)
        assertEquals(300, json["retry_after_s"]!!.jsonPrimitive.int)
    }
}
