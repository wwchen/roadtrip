package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityCell.Companion.AVAILABILITY_CELL
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import org.jooq.DSLContext
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import ca.floo.roadtrip.db.generated.enums.AvailabilityStatus as DbAvailabilityStatus

/**
 * The current face of the availability cube: one row per (reservable_id,
 * target_date), upserted on every poll. `last_observed_at` is bumped on every
 * observation (liveness); `status`/`last_changed_at` advance only when the
 * observed status differs from the stored one (edge). The executor uses the
 * per-row `changed` flag returned by [upsertObservations] to decide which
 * observations also warrant an append to the edge-triggered
 * `availability_snapshot` log.
 */
class AvailabilityCellRepo(
    private val ctx: DSLContext,
) {
    data class CellObservation(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val observedAt: Instant,
    )

    data class UpsertResult(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val changed: Boolean, // true iff status != prior status (edge)
    )

    data class Cell(
        val reservableId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val lastObservedAt: OffsetDateTime,
        val lastChangedAt: OffsetDateTime,
    )

    /**
     * Upserts every observation's cell unconditionally (last_observed_at always
     * bumped); returns one [UpsertResult] per input row flagging which ones
     * changed status, so the caller knows which to also snapshot. Each row is a
     * single CTE round-trip that reads the prior status, upserts, and reports
     * the transition. Correctness first; batch only if profiling says so
     * (see plan risk #3).
     */
    fun upsertObservations(observations: List<CellObservation>): List<UpsertResult> {
        if (observations.isEmpty()) return emptyList()
        return observations.map { obs ->
            val observedAt = OffsetDateTime.ofInstant(obs.observedAt, ZoneOffset.UTC)
            val newLiteral = obs.status.toDb().literal
            val row =
                ctx
                    .resultQuery(
                        """
                        WITH prior AS (
                            SELECT status FROM availability_cell
                            WHERE reservable_id = ? AND target_date = ?
                        ), upsert AS (
                            INSERT INTO availability_cell (reservable_id, target_date, status, last_observed_at, last_changed_at)
                            VALUES (?, ?, ?::availability_status, ?::timestamptz, ?::timestamptz)
                            ON CONFLICT (reservable_id, target_date) DO UPDATE SET
                                status = EXCLUDED.status,
                                last_observed_at = EXCLUDED.last_observed_at,
                                last_changed_at = CASE
                                    WHEN availability_cell.status IS DISTINCT FROM EXCLUDED.status
                                    THEN EXCLUDED.last_changed_at
                                    ELSE availability_cell.last_changed_at
                                END
                            RETURNING status
                        )
                        SELECT upsert.status AS new_status, prior.status AS old_status
                        FROM upsert LEFT JOIN prior ON true
                        """.trimIndent(),
                        obs.reservableId,
                        obs.targetDate,
                        obs.reservableId,
                        obs.targetDate,
                        newLiteral,
                        observedAt,
                        observedAt,
                    ).fetchOne()!!
            val oldStatus = row.get("old_status", String::class.java)
            UpsertResult(
                reservableId = obs.reservableId,
                targetDate = obs.targetDate,
                status = obs.status,
                changed = oldStatus == null || oldStatus != newLiteral,
            )
        }
    }

    /**
     * Returns the current cell for each (reservable_id, target_date) in the
     * cross product of the two inputs. One row per key already, so a plain
     * indexed point-read -- no DISTINCT ON. Missing pairs are absent.
     */
    fun loadCells(
        reservableIds: List<Long>,
        dates: List<LocalDate>,
    ): List<Cell> {
        if (reservableIds.isEmpty() || dates.isEmpty()) return emptyList()
        return ctx
            .selectFrom(AVAILABILITY_CELL)
            .where(AVAILABILITY_CELL.RESERVABLE_ID.`in`(reservableIds))
            .and(AVAILABILITY_CELL.TARGET_DATE.`in`(dates))
            .fetch { r ->
                Cell(
                    reservableId = r.get(AVAILABILITY_CELL.RESERVABLE_ID)!!,
                    targetDate = r.get(AVAILABILITY_CELL.TARGET_DATE)!!,
                    status = AvailabilityStatus.parse(r.get(AVAILABILITY_CELL.STATUS)?.literal),
                    lastObservedAt = r.get(AVAILABILITY_CELL.LAST_OBSERVED_AT)!!,
                    lastChangedAt = r.get(AVAILABILITY_CELL.LAST_CHANGED_AT)!!,
                )
            }
    }

    /**
     * Marks cells with target_date < today as status='past' where not already
     * 'past'. Called once per executor run (cheap, scoped to the run's own
     * reservable set) so a date that quietly ages out without ever being
     * re-observed still reaches its terminal state. Bumps last_changed_at on the
     * flip so the transition is visible on the cell face. Returns rows updated.
     */
    fun markElapsedAsPast(
        reservableIds: List<Long>,
        today: LocalDate,
    ): Int {
        if (reservableIds.isEmpty()) return 0
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return ctx
            .update(AVAILABILITY_CELL)
            .set(AVAILABILITY_CELL.STATUS, DbAvailabilityStatus.past)
            .set(AVAILABILITY_CELL.LAST_CHANGED_AT, now)
            .where(AVAILABILITY_CELL.RESERVABLE_ID.`in`(reservableIds))
            .and(AVAILABILITY_CELL.TARGET_DATE.lt(today))
            .and(AVAILABILITY_CELL.STATUS.ne(DbAvailabilityStatus.past))
            .execute()
    }
}

private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.entries.firstOrNull { it.literal == wireValue }
        ?: error("availability status has no DB enum literal: $wireValue")
