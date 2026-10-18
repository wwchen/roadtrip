package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.Availability.Companion.AVAILABILITY
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CellTransition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Duration
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
     * Three statements whatever the size: one read of the current row per cell,
     * one bump of the cells that did not change, one insert of the runs that did.
     * It used to be a SELECT plus a write *per observation*, which at a
     * campground's whole window is thousands of round trips holding a pooled
     * connection — the shape that put a stay check's write time in seconds.
     *
     * A cell named twice in one call is one observation of it: the latest
     * `observedAt` wins. Two rows chained in the same instant would invent a
     * status run nobody saw, and they cannot both chain off one current row.
     *
     * Two writers to the same cell can still both read the same current row and
     * both chain a run onto it — a drawer read overlapping a poll tick, or two
     * differently-filtered checks of one campground, which now cover the same
     * cells rather than disjoint ones. The loser's row is redundant (it says what
     * the winner just said, moments apart), so `ON CONFLICT DO NOTHING` drops it
     * and reports no transition for it, rather than failing the write and with it
     * a whole campground's request.
     */
    fun recordObservations(
        runId: Long?,
        observations: List<Observation>,
    ): List<CellTransition> {
        if (observations.isEmpty()) return emptyList()
        val latest = latestPerCell(observations)
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val current = currentStatusRuns(txn, latest.values)
            val unchanged = latest.values.filter { current[it.cell()]?.status == it.status }
            val changed = latest.values.filter { current[it.cell()]?.status != it.status }
            bumpObservedAt(txn, unchanged.mapNotNull { current[it.cell()]?.id }, unchanged.map { it.observedAt })
            insertStatusRuns(txn, changed, current, runId)
        }
    }

    private data class CurrentStatusRun(
        val id: Long,
        val status: AvailabilityStatus,
    )

    private fun Observation.cell(): Pair<Long, LocalDate> = campsiteId to targetDate

    /** One observation per cell: the latest the caller handed us. */
    private fun latestPerCell(observations: List<Observation>): Map<Pair<Long, LocalDate>, Observation> {
        val latest = LinkedHashMap<Pair<Long, LocalDate>, Observation>(observations.size)
        for (observation in observations) {
            val held = latest[observation.cell()]
            if (held == null || !observation.observedAt.isBefore(held.observedAt)) latest[observation.cell()] = observation
        }
        return latest
    }

    /** The current status-run of each cell, by cell; absent when the cell has none. */
    private fun currentStatusRuns(
        txn: DSLContext,
        observations: Collection<Observation>,
    ): Map<Pair<Long, LocalDate>, CurrentStatusRun> =
        txn
            .resultQuery(
                """
                SELECT DISTINCT ON (a.campsite_id, a.target_date)
                    a.id, a.campsite_id, a.target_date, a.status
                FROM availability a
                JOIN unnest(?::bigint[], ?::date[]) AS cell(campsite_id, target_date)
                  ON a.campsite_id = cell.campsite_id AND a.target_date = cell.target_date
                ORDER BY a.campsite_id, a.target_date, a.last_observed_at DESC, a.id DESC
                """.trimIndent(),
                observations.map { it.campsiteId }.toTypedArray(),
                observations.map { it.targetDate }.toTypedArray(),
            ).fetch { r ->
                (r.get("campsite_id", Long::class.java) to r.get("target_date", LocalDate::class.java)) to
                    CurrentStatusRun(
                        id = r.get("id", Long::class.java),
                        status = AvailabilityStatus.parse(r.get("status", String::class.java)),
                    )
            }.toMap()

    private fun bumpObservedAt(
        txn: DSLContext,
        ids: List<Long>,
        observedAts: List<Instant>,
    ) {
        if (ids.isEmpty()) return
        txn
            .query(
                """
                UPDATE availability AS a
                SET last_observed_at = seen.observed_at
                FROM unnest(?::bigint[], ?::timestamptz[]) AS seen(id, observed_at)
                WHERE a.id = seen.id
                """.trimIndent(),
                ids.toTypedArray(),
                observedAts.map { utc(it) }.toTypedArray(),
            ).execute()
    }

    /**
     * Inserts one status run per changed cell, chained to the row the read above
     * found current. Returns a transition only for the rows that actually landed:
     * `ON CONFLICT DO NOTHING` drops a run whose predecessor another writer has
     * already chained a successor onto.
     */
    private fun insertStatusRuns(
        txn: DSLContext,
        changed: List<Observation>,
        current: Map<Pair<Long, LocalDate>, CurrentStatusRun>,
        runId: Long?,
    ): List<CellTransition> {
        if (changed.isEmpty()) return emptyList()
        return txn
            .resultQuery(
                """
                INSERT INTO availability (campsite_id, target_date, status, last_observed_at, previous_id, run_id)
                SELECT run.campsite_id, run.target_date, run.status::availability_status,
                       run.observed_at, run.previous_id, ?::bigint
                FROM unnest(?::bigint[], ?::date[], ?::text[], ?::timestamptz[], ?::bigint[])
                     AS run(campsite_id, target_date, status, observed_at, previous_id)
                ON CONFLICT DO NOTHING
                RETURNING campsite_id, target_date, status
                """.trimIndent(),
                runId,
                changed.map { it.campsiteId }.toTypedArray(),
                changed.map { it.targetDate }.toTypedArray(),
                changed.map { it.status.toDb().literal }.toTypedArray(),
                changed.map { utc(it.observedAt) }.toTypedArray(),
                changed.map { current[it.cell()]?.id }.toTypedArray(),
            ).fetch { r ->
                CellTransition(
                    r.get("campsite_id", Long::class.java),
                    r.get("target_date", LocalDate::class.java),
                    AvailabilityStatus.parse(r.get("status", String::class.java)),
                )
            }
    }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

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
     * Per campsite, the earliest date from [from] on whose current observation
     * says it is bookable — the stored-fact answer to "when does this reopen".
     *
     * A base-unit read: it says nothing about whose question it is. The caller
     * passes the campsites its answer is about and reduces over them, which is
     * why a reopen date survives a cache hit — it is a fact in the table, not a
     * by-product of the call that happened to fetch.
     *
     * Reaches only as far as stored cells do: a window is stored as wide as the
     * fetch that covered it, so a campground whose season starts past that has no
     * answer here until a fetch reaches it.
     */
    fun earliestBookableDates(
        campsiteIds: List<Long>,
        from: LocalDate,
    ): Map<Long, LocalDate> {
        if (campsiteIds.isEmpty()) return emptyMap()
        return ctx
            .resultQuery(
                """
                SELECT campsite_id, min(target_date) AS reopens_on
                FROM (
                    SELECT DISTINCT ON (campsite_id, target_date)
                        campsite_id, target_date, status
                    FROM availability
                    WHERE campsite_id = ANY(?::bigint[])
                      AND target_date >= ?::date
                    ORDER BY campsite_id, target_date, last_observed_at DESC, id DESC
                ) cur
                WHERE cur.status::text = ANY(?::text[])
                GROUP BY campsite_id
                """.trimIndent(),
                campsiteIds.distinct().toTypedArray(),
                from,
                bookableLiterals,
            ).fetch { r ->
                r.get("campsite_id", Long::class.java) to r.get("reopens_on", LocalDate::class.java)
            }.toMap()
    }

    /**
     * Of [dates], the ones a **recent** observation says are NOT bookable.
     *
     * Deliberately asymmetric, and the asymmetry is the whole point. This table
     * is filled by the watch poller, so a campsite nobody watches has no recent
     * rows at all — which is the normal state for the browse-then-hold flow,
     * not a warning sign. Answering "which nights are known-available" and
     * refusing everything else made the direct add-to-cart unusable within
     * minutes of loading the grid: a genuinely bookable site was refused as
     * "not available" on the strength of an eight-minute-old observation that
     * actually said *available*.
     *
     * So only positive, fresh evidence of unavailability blocks. Silence —
     * a stale cell, or no cell at all — is not evidence, and the vendor is the
     * real arbiter anyway. [maxAge] decides what still counts as evidence, not
     * what counts as permission to proceed.
     */
    fun freshlyUnavailableDates(
        campsiteId: Long,
        dates: List<LocalDate>,
        maxAge: Duration,
        now: OffsetDateTime = OffsetDateTime.now(),
    ): Set<LocalDate> {
        if (dates.isEmpty()) return emptySet()
        val freshAfter = now.minus(maxAge)
        return readCurrent(listOf(campsiteId), dates)
            .filter { !it.available && it.observedAt.isAfter(freshAfter) }
            .map { it.targetDate }
            .toSet()
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
            // `availability.run_id` is nullable (ON DELETE SET NULL), and
            // `Long::class.java` is the primitive class — jOOQ would coerce that
            // NULL to 0 rather than leaving the run unattributed.
            runId = r.get("run_id", Long::class.javaObjectType),
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

/** The statuses that count as bookable, as the column stores them. One source: the enum. */
private val bookableLiterals: Array<String> =
    AvailabilityStatus.entries
        .filter { it.isOnlineBookable }
        .map { it.wireValue }
        .toTypedArray()

private fun AvailabilityStatus.toDb(): DbAvailabilityStatus =
    DbAvailabilityStatus.entries.firstOrNull { it.literal == wireValue }
        ?: error("availability status has no DB enum literal: $wireValue")
