package ca.floo.roadtrip.repo

import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime

class AvailabilityHeatmapRepo(
    private val ctx: DSLContext,
) {
    data class LatestCell(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: String,
        val available: Boolean,
        val observedAt: OffsetDateTime,
    )

    /**
     * For each (reservable_id, target_date) in the cross product of the two
     * inputs, return the most recent snapshot row. Cells with no snapshot
     * are not present in the result; the route layer fills them as null.
     *
     * Uses Postgres DISTINCT ON so the database returns one row per pair
     * directly, ordered by observed_at DESC. Cheaper than fetching all rows
     * and reducing in Kotlin once histories grow.
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
                SELECT DISTINCT ON (reservable_id, target_date)
                    reservable_id, target_date, status, available, observed_at
                FROM availability_snapshot
                WHERE reservable_id = ANY(?)
                  AND target_date = ANY(?)
                ORDER BY reservable_id, target_date, observed_at DESC
                """.trimIndent(),
                reservableIdsArg,
                datesArg,
            ).fetch { r ->
                LatestCell(
                    reservableId = r.get("reservable_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    status = r.get("status", String::class.java),
                    available = r.get("available", Boolean::class.java),
                    observedAt = r.get("observed_at", OffsetDateTime::class.java),
                )
            }
    }
}
