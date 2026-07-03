package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityFetchCall.Companion.AVAILABILITY_FETCH_CALL
import org.jooq.DSLContext
import org.jooq.Record
import java.time.LocalDate

/**
 * Trace layer between an [AvailabilityRunRepo] run and the raw upstream
 * calls it issued: one row per (provider, parent_ref) fetch a run made, at
 * the grouping granularity produced by CatalogAvailabilityBatcher. Written
 * only when a real upstream call was made.
 */
class AvailabilityFetchCallRepo(
    private val ctx: DSLContext,
) {
    data class NewCall(
        val runId: Long,
        val provider: String,
        val parentRef: String,
        val reservableCount: Int,
        val windowStart: LocalDate,
        val windowEnd: LocalDate,
        val outcome: String,
        val durationMs: Int?,
        val error: String?,
    )

    /**
     * Insert a fetch-call trace row. Returns the new row id.
     */
    fun record(call: NewCall): Long =
        ctx
            .insertInto(AVAILABILITY_FETCH_CALL)
            .set(AVAILABILITY_FETCH_CALL.RUN_ID, call.runId)
            .set(AVAILABILITY_FETCH_CALL.PROVIDER, call.provider)
            .set(AVAILABILITY_FETCH_CALL.PARENT_REF, call.parentRef)
            .set(AVAILABILITY_FETCH_CALL.RESERVABLE_COUNT, call.reservableCount)
            .set(AVAILABILITY_FETCH_CALL.WINDOW_START, call.windowStart)
            .set(AVAILABILITY_FETCH_CALL.WINDOW_END, call.windowEnd)
            .set(AVAILABILITY_FETCH_CALL.OUTCOME, call.outcome)
            .set(AVAILABILITY_FETCH_CALL.DURATION_MS, call.durationMs)
            .set(AVAILABILITY_FETCH_CALL.ERROR, call.error)
            .returningResult(AVAILABILITY_FETCH_CALL.ID)
            .fetchOne()!!
            .value1()!!

    /**
     * All fetch-call trace rows for a run, oldest-first (insertion order via id).
     */
    fun listForRun(runId: Long): List<NewCall> =
        ctx
            .selectFrom(AVAILABILITY_FETCH_CALL)
            .where(AVAILABILITY_FETCH_CALL.RUN_ID.eq(runId))
            .orderBy(AVAILABILITY_FETCH_CALL.ID.asc())
            .fetch { fromRecord(it) }

    private fun fromRecord(r: Record): NewCall =
        NewCall(
            runId = r.get(AVAILABILITY_FETCH_CALL.RUN_ID)!!,
            provider = r.get(AVAILABILITY_FETCH_CALL.PROVIDER)!!,
            parentRef = r.get(AVAILABILITY_FETCH_CALL.PARENT_REF)!!,
            reservableCount = r.get(AVAILABILITY_FETCH_CALL.RESERVABLE_COUNT)!!,
            windowStart = r.get(AVAILABILITY_FETCH_CALL.WINDOW_START)!!,
            windowEnd = r.get(AVAILABILITY_FETCH_CALL.WINDOW_END)!!,
            outcome = r.get(AVAILABILITY_FETCH_CALL.OUTCOME)!!,
            durationMs = r.get(AVAILABILITY_FETCH_CALL.DURATION_MS),
            error = r.get(AVAILABILITY_FETCH_CALL.ERROR),
        )
}
