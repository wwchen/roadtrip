package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityJobRun.Companion.AVAILABILITY_JOB_RUN
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

class AvailabilityJobRunRepo(
    private val ctx: DSLContext,
) {
    data class Run(
        val id: Long,
        val jobId: Long,
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
        jobId: Long,
        startedAt: OffsetDateTime,
    ): Long =
        ctx
            .insertInto(AVAILABILITY_JOB_RUN)
            .set(AVAILABILITY_JOB_RUN.JOB_ID, jobId)
            .set(AVAILABILITY_JOB_RUN.STATUS, "started")
            .set(AVAILABILITY_JOB_RUN.STARTED_AT, startedAt)
            .returningResult(AVAILABILITY_JOB_RUN.ID)
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
            .update(AVAILABILITY_JOB_RUN)
            .set(AVAILABILITY_JOB_RUN.STATUS, "completed")
            .set(AVAILABILITY_JOB_RUN.SNAPSHOT_COUNT, snapshotCount)
            .set(AVAILABILITY_JOB_RUN.DURATION_MS, durationMs)
            .set(AVAILABILITY_JOB_RUN.COMPLETED_AT, completedAt)
            .where(AVAILABILITY_JOB_RUN.ID.eq(runId))
            .and(AVAILABILITY_JOB_RUN.STATUS.eq("started"))
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
            .update(AVAILABILITY_JOB_RUN)
            .set(AVAILABILITY_JOB_RUN.STATUS, "failed")
            .set(AVAILABILITY_JOB_RUN.ERROR, error)
            .set(AVAILABILITY_JOB_RUN.DURATION_MS, durationMs)
            .set(AVAILABILITY_JOB_RUN.COMPLETED_AT, completedAt)
            .where(AVAILABILITY_JOB_RUN.ID.eq(runId))
            .and(AVAILABILITY_JOB_RUN.STATUS.eq("started"))
            .execute() > 0

    fun findById(id: Long): Run? =
        ctx
            .selectFrom(AVAILABILITY_JOB_RUN)
            .where(AVAILABILITY_JOB_RUN.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    fun listForJob(
        jobId: Long,
        limit: Int = 50,
    ): List<Run> =
        ctx
            .selectFrom(AVAILABILITY_JOB_RUN)
            .where(AVAILABILITY_JOB_RUN.JOB_ID.eq(jobId))
            .orderBy(AVAILABILITY_JOB_RUN.STARTED_AT.desc(), AVAILABILITY_JOB_RUN.ID.desc())
            .limit(limit.coerceIn(1, 500))
            .fetch { fromRecord(it) }

    private fun fromRecord(r: Record): Run =
        Run(
            id = r.get(AVAILABILITY_JOB_RUN.ID)!!,
            jobId = r.get(AVAILABILITY_JOB_RUN.JOB_ID)!!,
            status = r.get(AVAILABILITY_JOB_RUN.STATUS)!!,
            snapshotCount = r.get(AVAILABILITY_JOB_RUN.SNAPSHOT_COUNT)!!,
            durationMs = r.get(AVAILABILITY_JOB_RUN.DURATION_MS),
            error = r.get(AVAILABILITY_JOB_RUN.ERROR),
            startedAt = r.get(AVAILABILITY_JOB_RUN.STARTED_AT)!!,
            completedAt = r.get(AVAILABILITY_JOB_RUN.COMPLETED_AT),
        )
}
