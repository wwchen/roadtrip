package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityPoller.Companion.AVAILABILITY_POLLER
import ca.floo.roadtrip.db.generated.tables.AvailabilityRun.Companion.AVAILABILITY_RUN
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

/**
 * Caps the newest-first terminal-run scan in [AvailabilityRunRepo.countConsecutiveFailures]
 * so a permanently-failing poller can't force an unbounded fetch.
 */
private const val CONSECUTIVE_FAILURE_SCAN_LIMIT = 100

class AvailabilityRunRepo(
    private val ctx: DSLContext,
) {
    data class Run(
        val id: Long,
        val pollerId: Long,
        val status: String,
        val snapshotCount: Int,
        val durationMs: Int?,
        val error: String?,
        val startedAt: OffsetDateTime,
        val completedAt: OffsetDateTime?,
    )

    /**
     * Insert a new row at status='started'. Returns the new row id; the
     * executor passes it back to [complete] / [fail] when the handler
     * finishes.
     */
    fun start(
        pollerId: Long,
        startedAt: OffsetDateTime,
    ): Long =
        ctx
            .insertInto(AVAILABILITY_RUN)
            .set(AVAILABILITY_RUN.POLLER_ID, pollerId)
            .set(AVAILABILITY_RUN.STATUS, "started")
            .set(AVAILABILITY_RUN.STARTED_AT, startedAt)
            .returningResult(AVAILABILITY_RUN.ID)
            .fetchOne()!!
            .value1()!!

    /**
     * Mark a run completed. Idempotent: if the row is already in a
     * terminal state, this is a no-op (returns false). Otherwise updates
     * status to 'completed', records snapshot_count, duration_ms, and
     * completed_at, and returns true.
     */
    fun complete(
        runId: Long,
        snapshotCount: Int,
        completedAt: OffsetDateTime,
        durationMs: Int,
    ): Boolean =
        ctx
            .update(AVAILABILITY_RUN)
            .set(AVAILABILITY_RUN.STATUS, "completed")
            .set(AVAILABILITY_RUN.SNAPSHOT_COUNT, snapshotCount)
            .set(AVAILABILITY_RUN.DURATION_MS, durationMs)
            .set(AVAILABILITY_RUN.COMPLETED_AT, completedAt)
            .where(AVAILABILITY_RUN.ID.eq(runId))
            .and(AVAILABILITY_RUN.STATUS.eq("started"))
            .execute() > 0

    /**
     * Mark a run failed. Same idempotency contract as [complete]. The
     * error string is stored verbatim (truncated to ~2KB by the caller
     * if needed; Postgres TEXT has no enforced limit).
     */
    fun fail(
        runId: Long,
        error: String,
        completedAt: OffsetDateTime,
        durationMs: Int,
    ): Boolean =
        ctx
            .update(AVAILABILITY_RUN)
            .set(AVAILABILITY_RUN.STATUS, "failed")
            .set(AVAILABILITY_RUN.ERROR, error)
            .set(AVAILABILITY_RUN.DURATION_MS, durationMs)
            .set(AVAILABILITY_RUN.COMPLETED_AT, completedAt)
            .where(AVAILABILITY_RUN.ID.eq(runId))
            .and(AVAILABILITY_RUN.STATUS.eq("started"))
            .execute() > 0

    fun findById(id: Long): Run? =
        ctx
            .selectFrom(AVAILABILITY_RUN)
            .where(AVAILABILITY_RUN.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    fun listForPoller(
        pollerId: Long,
        limit: Int = 50,
    ): List<Run> =
        ctx
            .selectFrom(AVAILABILITY_RUN)
            .where(AVAILABILITY_RUN.POLLER_ID.eq(pollerId))
            .orderBy(AVAILABILITY_RUN.STARTED_AT.desc(), AVAILABILITY_RUN.ID.desc())
            .limit(limit.coerceIn(1, 500))
            .fetch { fromRecord(it) }

    /**
     * Recent runs across all pollers newest-first. Optional filters:
     * - [since]: only runs whose started_at is after this instant
     * - [status]: 'started' | 'completed' | 'failed'
     * - [pollerId]: scope to one poller (used by drill-down from Pollers tab)
     */
    fun listSince(
        since: OffsetDateTime? = null,
        status: String? = null,
        pollerId: Long? = null,
        limit: Int = 100,
    ): List<Run> {
        val conds = mutableListOf<org.jooq.Condition>()
        if (since != null) conds += AVAILABILITY_RUN.STARTED_AT.ge(since)
        if (status != null) conds += AVAILABILITY_RUN.STATUS.eq(status)
        if (pollerId != null) conds += AVAILABILITY_RUN.POLLER_ID.eq(pollerId)
        return ctx
            .selectFrom(AVAILABILITY_RUN)
            .where(
                if (conds.isEmpty()) {
                    org.jooq.impl.DSL
                        .noCondition()
                } else {
                    org.jooq.impl.DSL
                        .and(conds)
                },
            ).orderBy(AVAILABILITY_RUN.STARTED_AT.desc(), AVAILABILITY_RUN.ID.desc())
            .limit(limit.coerceIn(1, 500))
            .fetch { fromRecord(it) }
    }

    /**
     * Number of `failed` runs at the newest end of the poller's run history,
     * stopping at the first non-failed terminal run. 0 if the most recent
     * terminal run is `completed`. Only terminal statuses are considered;
     * in-flight `started` rows are ignored. Derived from run rows (source
     * of truth) rather than a maintained column, so there's no double-write
     * to keep in sync.
     */
    fun countConsecutiveFailures(pollerId: Long): Int {
        val statuses =
            ctx
                .select(AVAILABILITY_RUN.STATUS)
                .from(AVAILABILITY_RUN)
                .where(AVAILABILITY_RUN.POLLER_ID.eq(pollerId))
                .and(AVAILABILITY_RUN.STATUS.`in`("completed", "failed"))
                .orderBy(AVAILABILITY_RUN.STARTED_AT.desc(), AVAILABILITY_RUN.ID.desc())
                .limit(CONSECUTIVE_FAILURE_SCAN_LIMIT)
                .fetch(AVAILABILITY_RUN.STATUS)
        return statuses.takeWhile { it == "failed" }.count()
    }

    data class RunTimeRange(
        val totalRuns: Int,
        val firstStartedAt: OffsetDateTime,
        val lastStartedAt: OffsetDateTime,
        val medianCadenceSec: Int?,
    )

    fun timeRangeForPoi(poiId: Long): RunTimeRange? {
        val timestamps = runTimestampsForPoi(poiId)
        if (timestamps.isEmpty()) return null
        if (timestamps.size == 1) return RunTimeRange(1, timestamps.first(), timestamps.first(), null)
        return RunTimeRange(timestamps.size, timestamps.first(), timestamps.last(), medianGap(timestamps))
    }

    private fun runTimestampsForPoi(poiId: Long): List<OffsetDateTime> =
        ctx
            .select(AVAILABILITY_RUN.STARTED_AT)
            .from(AVAILABILITY_RUN)
            .join(AVAILABILITY_POLLER)
            .on(AVAILABILITY_RUN.POLLER_ID.eq(AVAILABILITY_POLLER.ID))
            .where(AVAILABILITY_POLLER.POI_ID.eq(poiId))
            .orderBy(AVAILABILITY_RUN.STARTED_AT.asc())
            .fetch { it.get(AVAILABILITY_RUN.STARTED_AT)!! }

    private fun medianGap(timestamps: List<OffsetDateTime>): Int? {
        if (timestamps.size < 2) return null
        val gaps =
            (1 until timestamps.size).map { i ->
                java.time.Duration
                    .between(timestamps[i - 1], timestamps[i])
                    .seconds
                    .toInt()
                    .coerceAtLeast(0)
            }
        val sorted = gaps.sorted()
        return sorted[sorted.size / 2]
    }

    private fun fromRecord(r: Record): Run =
        Run(
            id = r.get(AVAILABILITY_RUN.ID)!!,
            pollerId = r.get(AVAILABILITY_RUN.POLLER_ID)!!,
            status = r.get(AVAILABILITY_RUN.STATUS)!!,
            snapshotCount = r.get(AVAILABILITY_RUN.SNAPSHOT_COUNT)!!,
            durationMs = r.get(AVAILABILITY_RUN.DURATION_MS),
            error = r.get(AVAILABILITY_RUN.ERROR),
            startedAt = r.get(AVAILABILITY_RUN.STARTED_AT)!!,
            completedAt = r.get(AVAILABILITY_RUN.COMPLETED_AT),
        )
}
