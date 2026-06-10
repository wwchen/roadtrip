package ca.floo.campsite.recgov.booker.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class EventsRoutesTest {
    @Test
    fun `connected event data serializes resume cursor with dto`() {
        val json = Json.parseToJsonElement(connectedEventData(42L)).jsonObject

        assertEquals(42L, json["resumeFrom"]!!.jsonPrimitive.long)
    }
}
