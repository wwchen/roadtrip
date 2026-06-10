package ca.floo.campsite.recgov.booker.events

import ca.floo.campsite.recgov.booker.domain.Match
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class CampsiteMatchEventJsonTest {
    @Test
    fun `match found event data preserves legacy payload shape`() {
        val payload =
            matchFoundEventData(
                Match(
                    id = 42,
                    alertId = 7,
                    campgroundId = "232447",
                    campsiteId = "100",
                    campsiteSite = "A\"1",
                    campsiteLoop = null,
                    campsiteType = "STANDARD",
                    firstDate = "2026-07-01",
                    nights = 2,
                    availableDates = listOf("2026-07-01", "2026-07-02"),
                    foundAt = "2026-06-10T12:00:00Z",
                    notified = false,
                    campgroundName = "Upper Pines",
                ),
            )
        val json = Json.parseToJsonElement(payload).jsonObject

        assertEquals(
            setOf(
                "id",
                "alertId",
                "campgroundId",
                "campsiteId",
                "site",
                "loop",
                "campsiteType",
                "firstDate",
                "nights",
                "availableDates",
                "foundAt",
                "campgroundName",
            ),
            json.keys,
        )
        assertEquals(42, json["id"]!!.jsonPrimitive.long)
        assertEquals(7, json["alertId"]!!.jsonPrimitive.long)
        assertEquals("A\"1", json["site"]!!.jsonPrimitive.content)
        assertEquals("", json["loop"]!!.jsonPrimitive.content)
        assertEquals(2, json["nights"]!!.jsonPrimitive.int)
        assertEquals("2026-07-02", json["availableDates"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("Upper Pines", json["campgroundName"]!!.jsonPrimitive.content)
    }
}
