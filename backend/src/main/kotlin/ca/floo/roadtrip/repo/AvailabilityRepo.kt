package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.Availability.Companion.AVAILABILITY
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import ca.floo.roadtrip.db.generated.enums.AvailabilityStatus as DbAvailabilityStatus

/**
 * Sole owner of the `availability` interval table. Each row is a status-run for a
 * (reservable_id, target_date) cell. Writes bump `last_observed_at` in place while
 * status is unchanged and insert a new row (linked by `previous_id`) on a change;
 * reads take the row with the greatest `last_observed_at` per cell as current.
 */
class AvailabilityRepo(
    private val ctx: DSLContext,
) {
    data class Observation(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val observedAt: Instant,
    )

    data class CurrentCell(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val available: Boolean,
        val observedAt: OffsetDateTime,
    )

    /** Bump-or-insert each observation; returns the count of transitions (new rows). */
    fun recordObservations(
        runId: Long?,
        observations: List<Observation>,
    ): Int {
        if (observations.isEmpty()) return 0
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            var transitions = 0
            for (obs in observations) {
                val observedAt = OffsetDateTime.ofInstant(obs.observedAt, ZoneOffset.UTC)
                val current =
                    txn
                        .select(AVAILABILITY.ID, AVAILABILITY.STATUS)
                        .from(AVAILABILITY)
                        .where(AVAILABILITY.RESERVABLE_ID.eq(obs.reservableId))
                        .and(AVAILABILITY.TARGET_DATE.eq(obs.targetDate))
                        .orderBy(AVAILABILITY.LAST_OBSERVED_AT.desc(), AVAILABILITY.ID.desc())
                        .limit(1)
                        .fetchOne()
                val newStatus = obs.status.toDb()
                if (current != null && current.get(AVAILABILITY.STATUS) == newStatus) {
                    txn
                        .update(AVAILABILITY)
                        .set(AVAILABILITY.LAST_OBSERVED_AT, observedAt)
                        .where(AVAILABILITY.ID.eq(current.get(AVAILABILITY.ID)))
                        .execute()
                } else {
                    txn
                        .insertInto(AVAILABILITY)
                        .set(AVAILABILITY.RESERVABLE_ID, obs.reservableId)
                        .set(AVAILABILITY.TARGET_DATE, obs.targetDate)
                        .set(AVAILABILITY.STATUS, newStatus)
                        .set(AVAILABILITY.LAST_OBSERVED_AT, observedAt)
                        .set(AVAILABILITY.PREVIOUS_ID, current?.get(AVAILABILITY.ID))
                        .set(AVAILABILITY.RUN_ID, runId)
                        .execute()
                    transitions += 1
                }
            }
            transitions
        }
    }

    /** Current cell per (reservable, date): the row with the greatest last_observed_at. */
    fun readCurrent(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<CurrentCell> {
        if (reservableIds.isEmpty() || dates.isEmpty()) return emptyList()
        return ctx
            .resultQuery(
                """
                SELECT DISTINCT ON (reservable_id, target_date)
                    reservable_id, target_date, status, last_observed_at
                FROM availability
                WHERE reservable_id = ANY(?::bigint[])
                  AND target_date = ANY(?::date[])
                ORDER BY reservable_id, target_date, last_observed_at DESC, id DESC
                """.trimIndent(),
                reservableIds.toTypedArray(),
                dates.toTypedArray(),
            ).fetch { r ->
                val status = AvailabilityStatus.parse(r.get("status", String::class.java))
                CurrentCell(
                    reservableId = r.get("reservable_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    status = status,
                    available = status.isOnlineBookable,
                    observedAt = r.get("last_observed_at", OffsetDateTime::class.java),
                )
            }
    }
}

private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.entries.firstOrNull { it.literal == wireValue }
        ?: error("availability status has no DB enum literal: $wireValue")
