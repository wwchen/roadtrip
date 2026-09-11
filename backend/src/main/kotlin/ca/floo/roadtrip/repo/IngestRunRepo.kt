package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.model.domain.etl.ImportPhaseCounts
import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.ingest.RunKind
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

// encodeDefaults + explicitNulls=false: a POI_DATA phase omits the campsite
// counts entirely rather than writing nulls readers have to skip.
@OptIn(ExperimentalSerializationApi::class)
private val ingestCountsJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

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
            .set(INGEST_RUNS.PHASE, kind.rowValue)
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
    ): Long =
        ctx
            .insertInto(INGEST_RUNS)
            .set(INGEST_RUNS.TARGET, target)
            .set(INGEST_RUNS.PHASE, phase.label)
            .set(INGEST_RUNS.PHASE_KIND, "import")
            .set(INGEST_RUNS.PARENT_RUN_ID, parentId)
            .set(INGEST_RUNS.STATUS, "started")
            .set(INGEST_RUNS.TRIGGERED_BY, "phase")
            .set(INGEST_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .returningResult(INGEST_RUNS.ID)
            .fetchOne()!!
            .value1()!!

    fun completePhase(
        phaseId: Long,
        counts: ImportPhaseCounts,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "completed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.COUNTS, JSONB.valueOf(ingestCountsJson.encodeToString(counts)))
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
