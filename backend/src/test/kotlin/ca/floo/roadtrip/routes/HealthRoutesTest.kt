package ca.floo.roadtrip.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun `health response serializes status and epoch seconds with dto`() {
        val json = Json.parseToJsonElement(healthResponseJson(1717683240)).jsonObject

        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals(1717683240, json["now"]!!.jsonPrimitive.long)
    }
}
