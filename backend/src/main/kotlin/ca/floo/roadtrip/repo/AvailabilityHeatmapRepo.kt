package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime

class AvailabilityHeatmapRepo(
    private val ctx: DSLContext,
) {
    data class LatestCell(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val available: Boolean,
        val observedAt: OffsetDateTime,
    )

    /**
     * For each (reservable_id, target_date) in the cross product of the two
     * inputs, return the current cell from the availability cube. Cells never
     * observed are absent from the result; the route layer fills them as null.
     *
     * `availability_cell` holds exactly one row per (reservable_id, target_date)
     * — the cube's current face — so this is a plain indexed point-read, no
     * DISTINCT ON over the append log. `available` is derived from the status'
     * [AvailabilityStatus.isOnlineBookable] rather than a stored column (the
     * cell table has no denormalized `available` boolean; it was a
     * snapshot-only field). A cell with history but zero raw snapshot rows now
     * returns data where the old snapshot-backed query returned none — this is
     * the intended source-of-truth shift, not a regression.
     */
    fun loadHeatmap(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<LatestCell> {
        if (reservableIds.isEmpty() || dates.isEmpty()) return emptyList()
        val reservableIdsArg = reservableIds.toTypedArray()
        val datesArg = dates.toTypedArray()
        return ctx
            .resultQuery(
                """
                SELECT reservable_id, target_date, status, last_observed_at
                FROM availability_cell
                WHERE reservable_id = ANY(?::bigint[])
                  AND target_date = ANY(?::date[])
                """.trimIndent(),
                reservableIdsArg,
                datesArg,
            ).fetch { r ->
                val status = AvailabilityStatus.parse(r.get("status", String::class.java))
                LatestCell(
                    reservableId = r.get("reservable_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    status = status,
                    available = status.isOnlineBookable,
                    observedAt = r.get("last_observed_at", OffsetDateTime::class.java),
                )
            }
    }
}
