package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.Companion.AVAILABILITY_SNAPSHOT
import ca.floo.roadtrip.service.api.AvailabilityDayDto
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.availabilityResponseJson
import kotlinx.serialization.encodeToString
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Append-only per-day single-night availability snapshots. Replaces the
 * earlier reservable_availability_log table.
 *
 * One snapshot per (reservable_id, target_date, observed_at). Multi-
 * night availability is derived by combining consecutive target_date
 * rows from the same observed_at batch — the executor stores
 * single-night data even when the request was multi-night, so the
 * snapshot timeline shows real per-day state regardless of the original
 * query's min_nights.
 */
class AvailabilitySnapshotRepo(
    private val ctx: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class SnapshotBatch(
        val reservableId: Long,
        val runId: Long?,
        val response: AvailabilityResponseDto,
    )

    fun appendBatch(input: SnapshotBatch): Int {
        if (input.response.availability.isEmpty()) return 0

        val observedAt = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val inserts =
            input.response.availability.map { day ->
                ctx
                    .insertInto(AVAILABILITY_SNAPSHOT)
                    .set(AVAILABILITY_SNAPSHOT.RESERVABLE_ID, input.reservableId)
                    .set(AVAILABILITY_SNAPSHOT.RUN_ID, input.runId)
                    .set(AVAILABILITY_SNAPSHOT.OBSERVED_AT, observedAt)
                    .set(AVAILABILITY_SNAPSHOT.TARGET_DATE, LocalDate.parse(day.date))
                    .set(AVAILABILITY_SNAPSHOT.STATUS, day.status)
                    .set(AVAILABILITY_SNAPSHOT.AVAILABLE, day.availableCount > 0)
                    .set(AVAILABILITY_SNAPSHOT.DAY_PAYLOAD, JSONB.valueOf(day.toJson()))
            }
        ctx.batch(inserts).execute()
        return inserts.size
    }

    private fun AvailabilityDayDto.toJson(): String =
        availabilityResponseJson.encodeToString(AvailabilityDayDto.serializer(), this)
}
