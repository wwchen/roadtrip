package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvelopeTimestampsTest {
    @Test
    fun `parses the capture stamp`() {
        assertEquals(Instant.parse("2026-07-05T00:00:00Z"), envelopeStamped("2026-07-05T00:00:00Z").fetchedAtOrNow())
    }

    @Test
    fun `falls back to now when the stamp does not parse`() {
        val before = Instant.now()
        val parsed = envelopeStamped("yesterday-ish").fetchedAtOrNow()
        assertTrue(!parsed.isBefore(before) && !parsed.isAfter(Instant.now()))
    }

    private fun envelopeStamped(fetchedAt: String): Envelope =
        Json.decodeFromString(
            Envelope.serializer(),
            """
            { "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "$fetchedAt",
              "request": { "url": "test://stamp", "method": "GET" },
              "response": { "status": 200 },
              "payload": {} }
            """.trimIndent(),
        )
}
