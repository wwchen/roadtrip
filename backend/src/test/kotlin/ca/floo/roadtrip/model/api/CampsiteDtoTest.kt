package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.domain.CampsiteAttribute
import ca.floo.roadtrip.model.domain.CatalogPhoto
import ca.floo.roadtrip.route.common.roadtripApiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The row carries the whole DB record; the drawer gets the facts it renders and
// nothing else.
private val neverOnTheWire =
    listOf(
        "source_payload",
        "created_at",
        "schedule",
        "price",
        "booking_provider_ref",
        "latitude",
    )

private const val PHOTO_URL = "https://x/1.jpg"
private const val MIN_PEOPLE = 2

class CampsiteDtoTest {
    private val row =
        campsiteFixture(bookingProvider = null).copy(
            equipment = listOf("Tent", "RV"),
            photos = listOf(CatalogPhoto(PHOTO_URL)),
            attributes = listOf(CampsiteAttribute("Shade", "Partial"), CampsiteAttribute("Pets allowed")),
            minPeople = MIN_PEOPLE,
        )

    @Test
    fun `carries the facts the drawer renders and their values`() {
        assertEquals(
            roadtripApiJson.parseToJsonElement(
                """
                {
                  "id": 1,
                  "campground_id": 1,
                  "name": "Site 1",
                  "kind": "other",
                  "kind_label": "Other",
                  "min_people": 2,
                  "equipment": ["Tent", "RV"],
                  "attributes": [{"name": "Shade", "value": "Partial"}, {"name": "Pets allowed"}],
                  "photo_url": "$PHOTO_URL",
                  "data_provider": "recgov",
                  "data_provider_ref": "1"
                }
                """.trimIndent(),
            ),
            encoded(),
        )
    }

    @Test
    fun `leaves the row-only columns off the wire`() {
        val json = encoded()

        assertEquals(emptyList(), neverOnTheWire.filter(json::containsKey))
    }

    @Test
    fun `booking_system is omitted when unknown and present when served`() {
        assertNull(encoded()["booking_system"])
        val named = roadtripApiJson.encodeToJsonElement(CampsiteDto.from(row, bookingSystem = "BC Parks")).jsonObject
        assertEquals("BC Parks", named["booking_system"]?.jsonPrimitive?.content)
    }

    private fun encoded(): JsonObject = roadtripApiJson.encodeToJsonElement(CampsiteDto.from(row, bookingSystem = null)).jsonObject
}
