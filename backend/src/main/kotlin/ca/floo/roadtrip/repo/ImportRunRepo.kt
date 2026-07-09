package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ImportRuns.Companion.IMPORT_RUNS
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Sole writer of the `import_runs` table. The ETL upsert repos ([PoiRepo],
 * [CampsiteRepo]) open a run at the start of a source snapshot and terminate it
 * (completed/failed) at the end; this repo owns those three mutations so the run
 * lifecycle has a single owner rather than being duplicated per upsert repo.
 */
class ImportRunRepo(
    private val ctx: DSLContext,
) {
    /** Insert a `started` run for [source] and return its id. */
    fun start(source: String): Long =
        ctx
            .insertInto(IMPORT_RUNS)
            .set(IMPORT_RUNS.SOURCE, source)
            .set(IMPORT_RUNS.STATUS, "started")
            .set(IMPORT_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .returningResult(IMPORT_RUNS.ID)
            .fetchOne()!!
            .value1()!!

    /** Mark run [runId] completed with the number of rows seen this run. */
    fun complete(
        runId: Long,
        seenCount: Int,
    ) {
        ctx
            .update(IMPORT_RUNS)
            .set(IMPORT_RUNS.STATUS, "completed")
            .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(IMPORT_RUNS.SEEN_COUNT, seenCount)
            .where(IMPORT_RUNS.ID.eq(runId))
            .execute()
    }

    /** Mark run [runId] failed with a human-readable [notes] reason. */
    fun fail(
        runId: Long,
        notes: String,
    ) {
        ctx
            .update(IMPORT_RUNS)
            .set(IMPORT_RUNS.STATUS, "failed")
            .set(IMPORT_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(IMPORT_RUNS.NOTES, notes)
            .where(IMPORT_RUNS.ID.eq(runId))
            .execute()
    }
}
