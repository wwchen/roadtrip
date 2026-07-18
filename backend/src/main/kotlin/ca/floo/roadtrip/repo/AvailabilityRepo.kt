package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.Availability.Companion.AVAILABILITY
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CellTransition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import ca.floo.roadtrip.db.generated.enums.AvailabilityStatus as DbAvailabilityStatus

/**
 * Sole owner of the `availability` interval table. Each row is a status-run for a
 * (campsite_id, target_date) cell. Writes bump `last_observed_at` in place while
 * status is unchanged and insert a new row (linked by `previous_id`) on a change;
 * reads take the row with the greatest `last_observed_at` per cell as current.
 */
class AvailabilityRepo(
    private val ctx: DSLContext,
) {
    data class Observation(
        val campsiteId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val observedAt: Instant,
    )

    data class CurrentCell(
        val campsiteId: Long,
        val targetDate: LocalDate,
        val status: AvailabilityStatus,
        val available: Boolean,
        val observedAt: OffsetDateTime,
    )

    private val statusRunSelect =
        """
        SELECT a.campsite_id, a.run_id, a.target_date, a.status,
               COALESCE(r.completed_at, a.last_observed_at) AS fetched_at,
               lag(a.status) OVER w AS from_status
        FROM availability a
        LEFT JOIN availability_run r ON r.id = a.run_id
        WINDOW w AS (PARTITION BY a.campsite_id, a.target_date ORDER BY a.last_observed_at, a.id)
        """.trimIndent()

    /**
     * Bump-or-insert each observation; returns one [CellTransition] per status
     * change (new row inserted). Unchanged cells bump `last_observed_at` in place
     * and contribute no transition. The count of transitions is the caller's
     * `snapshot_count`; the bookable subset feeds alert dispatch.
     *
     * Known, accepted race: two writers to the SAME cell in the same instant (a
     * live drawer fetch overlapping a poll) can both read the current row and both
     * insert `previous_id` = that row, tripping `availability_previous_id_uq`. Left
     * unguarded — low-probability on this single-instance, low-write backend, and
     * self-healing: the poller records the tick as a failed run and re-polls next
     * cadence, and the drawer read is retryable. Serialize per-cell (advisory lock
     * or SELECT … FOR UPDATE) if it ever shows up in practice.
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
                        .where(AVAILABILITY.CAMPSITE_ID.eq(obs.campsiteId))
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
                        .set(AVAILABILITY.CAMPSITE_ID, obs.campsiteId)
                        .set(AVAILABILITY.TARGET_DATE, obs.targetDate)
                        .set(AVAILABILITY.STATUS, newStatus)
                        .set(AVAILABILITY.LAST_OBSERVED_AT, observedAt)
                        .set(AVAILABILITY.PREVIOUS_ID, current?.get(AVAILABILITY.ID))
                        .set(AVAILABILITY.RUN_ID, runId)
                        .execute()
                    transitions += CellTransition(obs.campsiteId, obs.targetDate, obs.status)
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
        campsiteIds: List<Long>,
        today: LocalDate,
    ): Int {
        if (campsiteIds.isEmpty()) return 0
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val elapsed =
                txn
                    .resultQuery(
                        """
                        SELECT id, campsite_id, target_date
                        FROM (
                            SELECT DISTINCT ON (campsite_id, target_date)
                                id, campsite_id, target_date, status
                            FROM availability
                            WHERE campsite_id = ANY(?::bigint[])
                              AND target_date < ?::date
                            ORDER BY campsite_id, target_date, last_observed_at DESC, id DESC
                        ) cur
                        WHERE cur.status <> 'past'
                        """.trimIndent(),
                        campsiteIds.toTypedArray(),
                        today,
                    ).fetch { it }
            for (row in elapsed) {
                txn
                    .insertInto(AVAILABILITY)
                    .set(AVAILABILITY.CAMPSITE_ID, row.get("campsite_id", Long::class.java))
                    .set(AVAILABILITY.TARGET_DATE, row.get("target_date", LocalDate::class.java))
                    .set(AVAILABILITY.STATUS, DbAvailabilityStatus.past)
                    .set(AVAILABILITY.LAST_OBSERVED_AT, now)
                    .set(AVAILABILITY.PREVIOUS_ID, row.get("id", Long::class.java))
                    .execute()
            }
            elapsed.size
        }
    }

    /** Current cell per (campsite, date): the row with the greatest last_observed_at. */
    fun readCurrent(
        campsiteIds: List<Long>,
        dates: List<LocalDate>,
    ): List<CurrentCell> {
        if (campsiteIds.isEmpty() || dates.isEmpty()) return emptyList()
        return ctx
            .resultQuery(
                """
                SELECT DISTINCT ON (campsite_id, target_date)
                    campsite_id, target_date, status, last_observed_at
                FROM availability
                WHERE campsite_id = ANY(?::bigint[])
                  AND target_date = ANY(?::date[])
                ORDER BY campsite_id, target_date, last_observed_at DESC, id DESC
                """.trimIndent(),
                campsiteIds.toTypedArray(),
                dates.toTypedArray(),
            ).fetch { r ->
                val status = AvailabilityStatus.parse(r.get("status", String::class.java))
                CurrentCell(
                    campsiteId = r.get("campsite_id", Long::class.java),
                    targetDate = r.get("target_date", LocalDate::class.java),
                    status = status,
                    available = status.isOnlineBookable,
                    observedAt = r.get("last_observed_at", OffsetDateTime::class.java),
                )
            }
    }

    /**
     * True when every campsite/date cell in `[startDate, endDate)` has a
     * current observation at or newer than [freshAtOrAfter]. Missing cells make
     * the window stale. Used by the alert poller to avoid re-fetching a whole
     * campground window moments after another tick already refreshed it.
     */
    fun hasFreshCoverage(
        campsiteIds: List<Long>,
        startDate: LocalDate,
        endDate: LocalDate,
        freshAtOrAfter: OffsetDateTime,
    ): Boolean {
        if (campsiteIds.isEmpty() || !endDate.isAfter(startDate)) return false
        val expectedCells =
            campsiteIds.size *
                ChronoUnit.DAYS.between(startDate, endDate)
        if (expectedCells <= 0) return false
        val freshCells =
            ctx
                .resultQuery(
                    """
                    SELECT count(*) AS fresh_cells
                    FROM (
                        SELECT DISTINCT ON (campsite_id, target_date)
                            campsite_id, target_date, last_observed_at
                        FROM availability
                        WHERE campsite_id = ANY(?::bigint[])
                          AND target_date >= ?::date
                          AND target_date < ?::date
                        ORDER BY campsite_id, target_date, last_observed_at DESC, id DESC
                    ) cur
                    WHERE cur.last_observed_at >= ?::timestamptz
                    """.trimIndent(),
                    campsiteIds.toTypedArray(),
                    startDate,
                    endDate,
                    freshAtOrAfter,
                ).fetchOne("fresh_cells", Long::class.java) ?: 0L
        return freshCells == expectedCells
    }

    data class StatusRun(
        val campsiteId: Long,
        val runId: Long?,
        val targetDate: LocalDate,
        val fromStatus: AvailabilityStatus?,
        val toStatus: AvailabilityStatus,
        val fetchedAt: OffsetDateTime,
    )

    private fun mapStatusRun(r: org.jooq.Record): StatusRun {
        val toStatus = AvailabilityStatus.parse(r.get("status", String::class.java))
        val fromStatusRaw = r.get("from_status", String::class.java)
        return StatusRun(
            campsiteId = r.get("campsite_id", Long::class.java),
            runId = r.get("run_id", Long::class.java),
            targetDate = r.get("target_date", LocalDate::class.java),
            fromStatus = fromStatusRaw?.let { AvailabilityStatus.parse(it) },
            toStatus = toStatus,
            fetchedAt = r.get("fetched_at", OffsetDateTime::class.java),
        )
    }

    fun listForCampsite(
        campsiteId: Long,
        targetDate: LocalDate? = null,
        limit: Int = 200,
    ): List<StatusRun> = listForCampsites(listOf(campsiteId), targetDate, limit)

    fun listForCampsites(
        campsiteIds: List<Long>,
        targetDate: LocalDate? = null,
        limit: Int = 500,
    ): List<StatusRun> {
        if (campsiteIds.isEmpty()) return emptyList()
        val dateClause = if (targetDate != null) " AND target_date = ?" else ""
        val sql =
            "SELECT * FROM ($statusRunSelect) t WHERE campsite_id = ANY(?::bigint[])" +
                "$dateClause ORDER BY target_date DESC, fetched_at DESC LIMIT ?"
        return if (targetDate != null) {
            ctx
                .resultQuery(sql, campsiteIds.toTypedArray(), targetDate, limit.coerceIn(1, 1000))
                .fetch { mapStatusRun(it) }
        } else {
            ctx
                .resultQuery(sql, campsiteIds.toTypedArray(), limit.coerceIn(1, 1000))
                .fetch { mapStatusRun(it) }
        }
    }

    // Dates observed in the availability window for a campsite, plus any date
    // >= today (so the summary still surfaces upcoming cells whose last
    // observation predates windowStart). One table now, so the "OR" replaces
    // the old union.
    fun datesWithSnapshotsInWindow(
        campsiteId: Long,
        windowStart: OffsetDateTime = OffsetDateTime.now().minusHours(DEFAULT_SUMMARY_WINDOW_HOURS.toLong()),
        today: LocalDate = LocalDate.now(),
    ): List<LocalDate> =
        ctx
            .selectDistinct(AVAILABILITY.TARGET_DATE)
            .from(AVAILABILITY)
            .where(AVAILABILITY.CAMPSITE_ID.eq(campsiteId))
            .and(
                AVAILABILITY.LAST_OBSERVED_AT
                    .ge(windowStart)
                    .or(AVAILABILITY.TARGET_DATE.ge(today)),
            ).fetch { it.value1() }
            .filterNotNull()
            .sorted()

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
        val firstRunAt: OffsetDateTime? = null,
        val lastRunAt: OffsetDateTime? = null,
        val medianCadenceSec: Int? = null,
        val lastOpenAt: OffsetDateTime?,
        val isCurrentlyOpen: Boolean,
        val minOpenWindowSec: Int? = null,
        val maxOpenWindowSec: Int? = null,
    )

    fun projectAvailabilityRuns(
        campsiteId: Long,
        dates: List<LocalDate>,
    ): List<TargetDateStats> {
        if (dates.isEmpty()) return emptyList()
        val rows =
            ctx
                .resultQuery(
                    """
                    SELECT a.campsite_id, a.run_id, a.target_date, a.status,
                           COALESCE(r.completed_at, a.last_observed_at) AS fetched_at,
                           lag(a.status) OVER w AS from_status
                    FROM availability a
                    LEFT JOIN availability_run r ON r.id = a.run_id
                    WHERE a.campsite_id = ? AND a.target_date = ANY(?::date[])
                    WINDOW w AS (PARTITION BY a.campsite_id, a.target_date ORDER BY a.last_observed_at, a.id)
                    ORDER BY a.target_date, a.last_observed_at, a.id
                    """.trimIndent(),
                    campsiteId,
                    dates.toTypedArray(),
                ).fetch { mapStatusRun(it) }
        val byDate = rows.groupBy { it.targetDate }
        return dates.map { d -> statsFor(d, byDate[d].orEmpty()) }
    }

    private fun statsFor(
        date: LocalDate,
        runs: List<StatusRun>,
    ): TargetDateStats {
        if (runs.isEmpty()) {
            return TargetDateStats(
                targetDate = date,
                totalRuns = 0,
                lastOpenAt = null,
                isCurrentlyOpen = false,
            )
        }
        val openRuns = runs.filter { it.toStatus.isOnlineBookable }
        val openWindows = mutableListOf<Int>()
        for (i in runs.indices) {
            if (runs[i].toStatus.isOnlineBookable && (runs[i].fromStatus == null || !runs[i].fromStatus!!.isOnlineBookable)) {
                val closeIdx = ((i + 1) until runs.size).firstOrNull { !runs[it].toStatus.isOnlineBookable }
                if (closeIdx != null) {
                    val sec =
                        java.time.Duration
                            .between(runs[i].fetchedAt, runs[closeIdx].fetchedAt)
                            .seconds
                            .toInt()
                            .coerceAtLeast(0)
                    openWindows.add(sec)
                }
            }
        }
        return TargetDateStats(
            targetDate = date,
            totalRuns = runs.size,
            lastOpenAt = openRuns.lastOrNull()?.fetchedAt,
            isCurrentlyOpen = runs.last().toStatus.isOnlineBookable,
            minOpenWindowSec = openWindows.minOrNull(),
            maxOpenWindowSec = openWindows.maxOrNull(),
        )
    }
}

private const val DEFAULT_SUMMARY_WINDOW_HOURS: Int = 24 * 7

private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.entries.firstOrNull { it.literal == wireValue }
        ?: error("availability status has no DB enum literal: $wireValue")
