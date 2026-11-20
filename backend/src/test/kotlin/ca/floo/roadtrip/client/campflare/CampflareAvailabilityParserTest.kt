package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.fixtures.mangledAvailabilityFixture
import ca.floo.roadtrip.model.availability.AvailabilityPayloadException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val observedAt: Instant = Instant.parse("2026-06-01T00:00:00Z")

class CampflareAvailabilityParserTest {
    @Test
    fun `a bulk answer without its campgrounds array is unreadable, not empty`() {
        val thrown = assertUnreadable(mangledAvailabilityFixture("campflare-bulk-renamed-campgrounds.json"))

        assertEquals("campflare", thrown.provider)
        assertTrue(thrown.message!!.contains("campgrounds"), thrown.message)
    }

    @Test
    fun `a campground row without its id is unreadable rather than dropped`() {
        val thrown = assertUnreadable("""{"campgrounds":[{"campsite_availability":[]}]}""")

        assertTrue(thrown.message!!.contains("campground_id"), thrown.message)
    }

    @Test
    fun `a campsite row without its id is unreadable rather than dropped`() {
        val thrown =
            assertUnreadable("""{"campgrounds":[{"campground_id":"cg","campsite_availability":[{"availability":{}}]}]}""")

        assertTrue(thrown.message!!.contains("campsite_id"), thrown.message)
    }

    @Test
    fun `a campsite row without its availability is unreadable rather than unobserved`() {
        val thrown =
            assertUnreadable("""{"campgrounds":[{"campground_id":"cg","campsite_availability":[{"campsite_id":"s1"}]}]}""")

        assertTrue(thrown.message!!.contains("availability"), thrown.message)
        assertTrue(thrown.message!!.contains("s1"), thrown.message)
    }

    @Test
    fun `a date key that does not parse is unreadable rather than dropped`() {
        val thrown =
            assertUnreadable(
                """{"campgrounds":[{"campground_id":"cg","campsite_availability":[{"campsite_id":"s1","availability":{"06/01/2026":"available"}}]}]}""",
            )

        assertTrue(thrown.message!!.contains("06/01/2026"), thrown.message)
    }

    @Test
    fun `a status that is not a string is unreadable`() {
        val thrown =
            assertUnreadable(
                """{"campgrounds":[{"campground_id":"cg","campsite_availability":[{"campsite_id":"s1","availability":{"2026-06-01":null}}]}]}""",
            )

        assertTrue(thrown.message!!.contains("2026-06-01"), thrown.message)
    }

    @Test
    fun `an empty campgrounds array is a well-formed empty answer`() {
        assertEquals(emptyMap(), CampflareAvailabilityParser.parse(payload("""{"campgrounds":[]}"""), observedAt).campgrounds)
    }

    private fun assertUnreadable(json: String): AvailabilityPayloadException =
        assertFailsWith<AvailabilityPayloadException> { CampflareAvailabilityParser.parse(payload(json), observedAt) }

    private fun payload(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
}
