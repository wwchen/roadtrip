package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Backend restart mid-run leaves parent ingest_runs rows in 'started' forever
// — the IngestController coroutine that owned them is gone. Sweep stale rows
// to 'aborted' on boot so the dashboard doesn't show ghosts. Touches only
// ingest_runs; pois data may have been partially upserted by a kotlin phase
// (mark-and-sweep tolerates this — see RFC 0004 edge case #2).
//
// Threshold default is 30 minutes, well beyond the longest expected phase
// (rec.gov enricher tops out near 10 min today).
fun sweepStaleIngestRuns(
    ctx: DSLContext,
    staleAfter: java.time.Duration = java.time.Duration.ofMinutes(30),
): Int {
    val log = LoggerFactory.getLogger("BootRecovery")
    val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(staleAfter)
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    val swept =
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "aborted")
            .set(INGEST_RUNS.COMPLETED_AT, now)
            .set(INGEST_RUNS.NOTES, "boot recovery; phase orphaned")
            .where(INGEST_RUNS.STATUS.eq("started"))
            .and(INGEST_RUNS.STARTED_AT.lt(cutoff))
            .execute()
    if (swept > 0) log.info("boot recovery: marked {} ingest_runs rows as aborted", swept)
    return swept
}
