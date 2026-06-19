package ca.floo.roadtrip.repo

import ca.floo.roadtrip.service.api.AvailabilityStatus
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
     * inputs, return the most recent snapshot row. Cells with no snapshot
     * are not present in the result; the route layer fills them as null.
     *
     * Uses Postgres DISTINCT ON so the database returns one row per pair
     * directly, ordered by observed_at DESC, id DESC. Cheaper than fetching
     * all rows and reducing in Kotlin once histories grow.
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
                WHERE reservable_id = ANY(?::bigint[])
                  AND target_date = ANY(?::date[])
                ORDER BY reservable_id, target_date, observed_at DESC, id DESC
                """.trimIndent(),
                reservableIdsArg,
                datesArg,
            ).fetch { r ->
                LatestCell(
                    reservableId = r.get("reservable_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    status = AvailabilityStatus.parse(r.get("status", String::class.java)),
                    available = r.get("available", Boolean::class.java),
                    observedAt = r.get("observed_at", OffsetDateTime::class.java),
                )
            }
    }
}
