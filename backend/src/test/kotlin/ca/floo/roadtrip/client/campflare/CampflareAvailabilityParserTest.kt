package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.fixtures.mangledAvailabilityFixture
import ca.floo.roadtrip.model.availability.AvailabilityPayloadException
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val observedAt: Instant = Instant.parse("2026-06-01T00:00:00Z")

class CampflareAvailabilityParserTest {
    private fun parse(statusByDate: Map<String, String>) =
        CampflareAvailabilityParser.parse(
            Json
                .parseToJsonElement(
                    """
                    {"campgrounds": [{"campground_id": "cg", "campsite_availability": [
                      {"campsite_id": "s1", "availability": {${statusByDate.entries.joinToString { (d, s) -> "\"$d\": \"$s\"" }}}}
                    ]}]}
                    """.trimIndent(),
                ).jsonObject,
            Instant.EPOCH,
        )

    @Test
    fun `a listed status is classified and nothing is left unrecognized`() {
        val parsed =
            parse(
                mapOf(
                    "2026-07-01" to "available",
                    "2026-07-02" to "reserved",
                    "2026-07-03" to "closed",
                    "2026-07-04" to "first-come-first-serve",
                    "2026-07-05" to "not-yet-released",
                ),
            )

        assertEquals(
            listOf(
                AvailabilityStatus.AVAILABLE,
                AvailabilityStatus.RESERVED,
                AvailabilityStatus.CLOSED,
                AvailabilityStatus.FIRST_COME,
                AvailabilityStatus.UNKNOWN,
            ),
            (1..5).map {
                parsed.campgrounds
                    .getValue("cg")
                    .campsiteAvailability
                    .single()
                    .availability[LocalDate.of(2026, 7, it)]
            },
        )
        assertEquals(emptyMap(), parsed.unrecognizedStatuses)
    }

    @Test
    fun `an unlisted status is unknown and counted under its verbatim value`() {
        val parsed = parse(mapOf("2026-07-01" to "Lottery", "2026-07-02" to "Lottery", "2026-07-03" to "available"))

        assertEquals(
            AvailabilityStatus.UNKNOWN,
            parsed.campgrounds
                .getValue("cg")
                .campsiteAvailability
                .single()
                .availability[LocalDate.of(2026, 7, 1)],
        )
        assertEquals(mapOf("Lottery" to 2), parsed.unrecognizedStatuses)
    }

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
