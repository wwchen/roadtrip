package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.Availability.Companion.AVAILABILITY
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.CellTransition
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

    /**
     * Bump-or-insert each observation; returns one [CellTransition] per status
     * change (new row inserted). Unchanged cells bump `last_observed_at` in place
     * and contribute no transition. The count of transitions is the caller's
     * `snapshot_count`; the bookable subset feeds alert dispatch.
     */
    fun recordObservations(
        runId: Long?,
        observations: List<Observation>,
    ): List<CellTransition> {
        if (observations.isEmpty()) return emptyList()
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val transitions = mutableListOf<CellTransition>()
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
                    transitions += CellTransition(obs.reservableId, obs.targetDate, obs.status)
                }
            }
            transitions
        }
    }

    /**
     * Insert a terminal `past` status-run for every cell whose current row has an
     * elapsed target_date and is not already `past`. Chained via previous_id so the
     * transition is visible in history. Returns rows inserted.
     *
     * The elapsed lookup takes the current row per cell first (unfiltered top-1) and
     * only then drops cells already `past`. Filtering before the top-1 pick would let
     * an older non-`past` row resurface once the current row is `past`, and chaining a
     * second `past` run onto it would violate the previous_id uniqueness — so this must
     * be idempotent across repeated polls.
     */
    fun markElapsedAsPast(
        reservableIds: List<Long>,
        today: LocalDate,
    ): Int {
        if (reservableIds.isEmpty()) return 0
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val elapsed =
                txn
                    .resultQuery(
                        """
                        SELECT id, reservable_id, target_date
                        FROM (
                            SELECT DISTINCT ON (reservable_id, target_date)
                                id, reservable_id, target_date, status
                            FROM availability
                            WHERE reservable_id = ANY(?::bigint[])
                              AND target_date < ?::date
                            ORDER BY reservable_id, target_date, last_observed_at DESC, id DESC
                        ) cur
                        WHERE cur.status <> 'past'
                        """.trimIndent(),
                        reservableIds.toTypedArray(),
                        today,
                    ).fetch { it }
            for (row in elapsed) {
                txn
                    .insertInto(AVAILABILITY)
                    .set(AVAILABILITY.RESERVABLE_ID, row.get("reservable_id", Long::class.java))
                    .set(AVAILABILITY.TARGET_DATE, row.get("target_date", LocalDate::class.java))
                    .set(AVAILABILITY.STATUS, DbAvailabilityStatus.past)
                    .set(AVAILABILITY.LAST_OBSERVED_AT, now)
                    .set(AVAILABILITY.PREVIOUS_ID, row.get("id", Long::class.java))
                    .execute()
            }
            elapsed.size
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

    data class StatusRun(
        val reservableId: Long,
        val runId: Long?,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val available: Boolean,
        val observedFrom: OffsetDateTime?,
        val lastObservedAt: OffsetDateTime,
    )

    private val statusRunSelect =
        """
        SELECT reservable_id, run_id, target_date, status, last_observed_at,
               lag(last_observed_at) OVER (
                 PARTITION BY reservable_id, target_date ORDER BY last_observed_at, id
               ) AS observed_from
        FROM availability
        """.trimIndent()

    private fun mapStatusRun(r: org.jooq.Record): StatusRun {
        val status = AvailabilityStatus.parse(r.get("status", String::class.java))
        return StatusRun(
            reservableId = r.get("reservable_id", Long::class.java),
            runId = r.get("run_id", Long::class.java),
            targetDate = r.get("target_date", LocalDate::class.java),
            status = status,
            available = status.isOnlineBookable,
            observedFrom = r.get("observed_from", OffsetDateTime::class.java),
            lastObservedAt = r.get("last_observed_at", OffsetDateTime::class.java),
        )
    }

    fun listForReservable(
        reservableId: Long,
        limit: Int = 200,
    ): List<StatusRun> =
        ctx
            .resultQuery(
                "SELECT * FROM ($statusRunSelect) t WHERE reservable_id = ? " +
                    "ORDER BY target_date DESC, last_observed_at DESC LIMIT ?",
                reservableId,
                limit.coerceIn(1, 1000),
            ).fetch { mapStatusRun(it) }

    fun listForRun(
        runId: Long,
        limit: Int = 500,
    ): List<StatusRun> =
        ctx
            .resultQuery(
                "SELECT * FROM ($statusRunSelect) t WHERE run_id = ? ORDER BY target_date ASC LIMIT ?",
                runId,
                limit.coerceIn(1, 1000),
            ).fetch { mapStatusRun(it) }

    data class TargetDateStats(
        val targetDate: LocalDate,
        val totalRuns: Int,
        val lastOpenAt: OffsetDateTime?,
        val isCurrentlyOpen: Boolean,
        val currentOrLastOpenWindowSec: Int?,
        val medianOpenWindowSec: Int?,
        val opensLast24h: Int,
    )

    fun summarize(
        reservableId: Long,
        dates: List<LocalDate>,
        now: OffsetDateTime = OffsetDateTime.now(),
        windowHours: Int = DEFAULT_SUMMARY_WINDOW_HOURS,
    ): List<TargetDateStats> {
        if (dates.isEmpty()) return emptyList()
        val windowStart = now.minusHours(windowHours.toLong())
        val opensSince = now.minusHours(24)
        val rows =
            ctx
                .resultQuery(
                    "SELECT * FROM ($statusRunSelect) t WHERE reservable_id = ? " +
                        "AND target_date = ANY(?::date[]) AND last_observed_at >= ?::timestamptz " +
                        "ORDER BY target_date, last_observed_at",
                    reservableId,
                    dates.toTypedArray(),
                    windowStart,
                ).fetch { mapStatusRun(it) }
        val byDate = rows.groupBy { it.targetDate }
        return dates.map { d -> statsFor(d, byDate[d].orEmpty(), opensSince) }
    }

    private fun statsFor(
        date: LocalDate,
        runs: List<StatusRun>,
        opensSince: OffsetDateTime,
    ): TargetDateStats {
        if (runs.isEmpty()) {
            return TargetDateStats(date, 0, null, false, null, null, 0)
        }
        val openRuns = runs.filter { it.available }
        val openWindows =
            openRuns.map { r ->
                val from = r.observedFrom ?: r.lastObservedAt
                java.time.Duration
                    .between(from, r.lastObservedAt)
                    .seconds
                    .toInt()
                    .coerceAtLeast(0)
            }
        return TargetDateStats(
            targetDate = date,
            totalRuns = runs.size,
            lastOpenAt = openRuns.lastOrNull()?.lastObservedAt,
            isCurrentlyOpen = runs.last().available,
            currentOrLastOpenWindowSec = openWindows.lastOrNull(),
            medianOpenWindowSec = medianOrNull(openWindows),
            opensLast24h = openRuns.count { (it.observedFrom ?: it.lastObservedAt) >= opensSince },
        )
    }

    private fun medianOrNull(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
    }
}

private const val DEFAULT_SUMMARY_WINDOW_HOURS: Int = 24 * 7

private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.entries.firstOrNull { it.literal == wireValue }
        ?: error("availability status has no DB enum literal: $wireValue")
