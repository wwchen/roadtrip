package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.Envelope
import java.time.Instant
import java.time.format.DateTimeParseException

/** The capture's `fetched_at`, or now when the stamp does not parse. */
fun Envelope.fetchedAtOrNow(): Instant =
    try {
        Instant.parse(fetchedAt)
    } catch (e: DateTimeParseException) {
        Instant.now()
    }
