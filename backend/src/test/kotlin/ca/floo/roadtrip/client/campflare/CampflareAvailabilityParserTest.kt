package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
