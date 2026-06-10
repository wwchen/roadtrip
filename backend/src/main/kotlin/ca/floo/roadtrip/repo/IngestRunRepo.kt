package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.models.ingest.Phase
import ca.floo.roadtrip.models.ingest.RunKind
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

class IngestRunRepo(
    private val ctx: DSLContext,
) {
    fun createParentRow(
        target: String,
        kind: RunKind,
        triggeredBy: String,
    ): Long =
        ctx
            .insertInto(INGEST_RUNS)
            .set(INGEST_RUNS.TARGET, target)
            .set(INGEST_RUNS.PHASE, kind.rowValue) // 'fetch' or 'import'
            .set(INGEST_RUNS.PHASE_KIND, "target")
            .set(INGEST_RUNS.STATUS, "started")
            .set(INGEST_RUNS.TRIGGERED_BY, triggeredBy)
            .set(INGEST_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .returningResult(INGEST_RUNS.ID)
            .fetchOne()!!
            .value1()!!

    fun createPhaseRow(
        parentId: Long,
        target: String,
        phase: Phase,
    ): Long {
        val kind =
            when (phase) {
                is Phase.Fetch -> "fetch"
                is Phase.Import -> "import"
            }
        return ctx
            .insertInto(INGEST_RUNS)
            .set(INGEST_RUNS.TARGET, target)
            .set(INGEST_RUNS.PHASE, phase.label)
            .set(INGEST_RUNS.PHASE_KIND, kind)
            .set(INGEST_RUNS.PARENT_RUN_ID, parentId)
            .set(INGEST_RUNS.STATUS, "started")
            .set(INGEST_RUNS.TRIGGERED_BY, "phase")
            .set(INGEST_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .returningResult(INGEST_RUNS.ID)
            .fetchOne()!!
            .value1()!!
    }

    fun completePhase(
        phaseId: Long,
        counts: JSONB,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "completed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.COUNTS, counts)
            .where(INGEST_RUNS.ID.eq(phaseId))
            .execute()
    }

    fun failPhase(
        phaseId: Long,
        notes: String,
        exitCode: Int?,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "failed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.NOTES, notes)
            .apply { if (exitCode != null) set(INGEST_RUNS.EXIT_CODE, exitCode) }
            .where(INGEST_RUNS.ID.eq(phaseId))
            .execute()
    }

    fun completeParent(parentId: Long) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "completed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(INGEST_RUNS.ID.eq(parentId))
            .execute()
    }

    fun failParent(
        parentId: Long,
        notes: String,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "failed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.NOTES, notes)
            .where(INGEST_RUNS.ID.eq(parentId))
            .execute()
    }

    fun abortStaleStartedRows(staleAfter: Duration): Int {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val cutoff = now.minus(staleAfter)
        return ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "aborted")
            .set(INGEST_RUNS.COMPLETED_AT, now)
            .set(INGEST_RUNS.NOTES, "boot recovery; phase orphaned")
            .where(INGEST_RUNS.STATUS.eq("started"))
            .and(INGEST_RUNS.STARTED_AT.lt(cutoff))
            .execute()
    }
}
