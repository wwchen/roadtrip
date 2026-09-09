package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.domain.CampsiteAttribute
import ca.floo.roadtrip.model.domain.CatalogPhoto
import ca.floo.roadtrip.route.common.roadtripApiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
        campsiteFixture().copy(
            photos = listOf(CatalogPhoto(PHOTO_URL)),
            attributes = listOf(CampsiteAttribute("Shade", "Partial")),
            minPeople = MIN_PEOPLE,
        )

    @Test
    fun `carries the first photo, the attributes and min_people`() {
        val json = encoded()

        assertEquals(PHOTO_URL, json.getValue("photo_url").jsonPrimitive.content)
        assertEquals(
            "Shade",
            json
                .getValue("attributes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content,
        )
        assertEquals(MIN_PEOPLE, json.getValue("min_people").jsonPrimitive.int)
    }

    @Test
    fun `leaves the row-only columns off the wire`() {
        val json = encoded()

        assertEquals(emptyList(), neverOnTheWire.filter(json::containsKey))
    }

    private fun encoded(): JsonObject = roadtripApiJson.encodeToJsonElement(CampsiteDto.from(row)).jsonObject
}
