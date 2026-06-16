package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityJob.Companion.AVAILABILITY_JOB
import ca.floo.roadtrip.service.scheduler.Schedulable
import ca.floo.roadtrip.service.scheduler.SchedulableRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class AvailabilityJobRepo(
    private val ctx: DSLContext,
) : SchedulableRepo<AvailabilityJobRepo.Job> {
    private val json = Json

    data class Job(
        override val id: Long,
        val watchId: Long,
        val intentPayload: JsonObject,
        val cadenceSec: Int,
        val status: String,
        val nextRunAt: OffsetDateTime,
        val claimedUntil: OffsetDateTime?,
        override val claimToken: String?,
        val lastRunAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    ) : Schedulable

    /**
     * Atomically create or refresh the job backing a watch. Called whenever
     * a watch is created, updated, paused, or resumed. Re-uses the existing
     * row if it exists so jobs and watches stay 1:1.
     */
    fun upsertForWatch(
        watchId: Long,
        intentPayload: JsonObject,
        cadenceSec: Int,
        status: String,
        nextRunAt: OffsetDateTime,
    ): Job {
        require(status in setOf("active", "paused", "done")) { "invalid status" }
        ctx
            .insertInto(AVAILABILITY_JOB)
            .set(AVAILABILITY_JOB.WATCH_ID, watchId)
            .set(AVAILABILITY_JOB.INTENT_PAYLOAD, intentPayload.toJSONB())
            .set(AVAILABILITY_JOB.CADENCE_SEC, cadenceSec)
            .set(AVAILABILITY_JOB.STATUS, status)
            .set(AVAILABILITY_JOB.NEXT_RUN_AT, nextRunAt)
            .onConflict(AVAILABILITY_JOB.WATCH_ID)
            .doUpdate()
            .set(AVAILABILITY_JOB.INTENT_PAYLOAD, intentPayload.toJSONB())
            .set(AVAILABILITY_JOB.CADENCE_SEC, cadenceSec)
            .set(AVAILABILITY_JOB.STATUS, status)
            .set(AVAILABILITY_JOB.NEXT_RUN_AT, nextRunAt)
            .set(AVAILABILITY_JOB.UPDATED_AT, OffsetDateTime.now())
            .execute()
        return findByWatchId(watchId)!!
    }

    fun findById(id: Long): Job? =
        ctx
            .selectFrom(AVAILABILITY_JOB)
            .where(AVAILABILITY_JOB.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    fun findByWatchId(watchId: Long): Job? =
        ctx
            .selectFrom(AVAILABILITY_JOB)
            .where(AVAILABILITY_JOB.WATCH_ID.eq(watchId))
            .fetchOne()
            ?.let(::fromRecord)

    fun deleteForWatch(watchId: Long): Boolean = ctx.deleteFrom(AVAILABILITY_JOB).where(AVAILABILITY_JOB.WATCH_ID.eq(watchId)).execute() > 0

    /**
     * Claim up to [limit] active jobs whose next_run_at has passed. Sets
     * status untouched ("active" only — paused/done rows are ignored). Lease
     * extends `claimed_until` by [leaseDuration]; expired or null leases are
     * eligible. Returns the rows the caller now owns.
     *
     * Postgres `FOR UPDATE SKIP LOCKED` means parallel scheduler ticks (or a
     * future second worker) won't hand the same row to two callers.
     */
    override fun claimDue(
        now: OffsetDateTime,
        limit: Int,
        leaseDuration: Duration,
    ): List<Job> {
        val token = UUID.randomUUID().toString()
        val leaseUntil = now.plus(leaseDuration)
        // Two-step claim: SELECT … FOR UPDATE SKIP LOCKED, then UPDATE the
        // selected ids. Done in a single transaction.
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val due =
                txn
                    .select(AVAILABILITY_JOB.ID)
                    .from(AVAILABILITY_JOB)
                    .where(AVAILABILITY_JOB.STATUS.eq("active"))
                    .and(AVAILABILITY_JOB.NEXT_RUN_AT.le(now))
                    .and(
                        AVAILABILITY_JOB.CLAIMED_UNTIL.isNull
                            .or(AVAILABILITY_JOB.CLAIMED_UNTIL.lt(now)),
                    ).orderBy(AVAILABILITY_JOB.NEXT_RUN_AT.asc())
                    .limit(limit)
                    .forUpdate()
                    .skipLocked()
                    .fetch(AVAILABILITY_JOB.ID)
            if (due.isEmpty()) return@transactionResult emptyList()
            txn
                .update(AVAILABILITY_JOB)
                .set(AVAILABILITY_JOB.CLAIM_TOKEN, token)
                .set(AVAILABILITY_JOB.CLAIMED_UNTIL, leaseUntil)
                .set(AVAILABILITY_JOB.UPDATED_AT, now)
                .where(AVAILABILITY_JOB.ID.`in`(due))
                .execute()
            txn
                .selectFrom(AVAILABILITY_JOB)
                .where(AVAILABILITY_JOB.ID.`in`(due))
                .fetch { fromRecord(it) }
        }
    }

    /**
     * Release a claimed job after the worker finishes. Verifies the
     * claim_token matches; mismatched calls (lease expired, reclaimed)
     * return false without modifying the row.
     */
    override fun release(
        id: Long,
        token: String,
        nextRunAt: OffsetDateTime,
        ranAt: OffsetDateTime,
    ): Boolean =
        ctx
            .update(AVAILABILITY_JOB)
            .set(AVAILABILITY_JOB.CLAIM_TOKEN, null as String?)
            .set(AVAILABILITY_JOB.CLAIMED_UNTIL, null as OffsetDateTime?)
            .set(AVAILABILITY_JOB.NEXT_RUN_AT, nextRunAt)
            .set(AVAILABILITY_JOB.LAST_RUN_AT, ranAt)
            .set(AVAILABILITY_JOB.UPDATED_AT, ranAt)
            .where(AVAILABILITY_JOB.ID.eq(id))
            .and(AVAILABILITY_JOB.CLAIM_TOKEN.eq(token))
            .execute() > 0

    /**
     * Boot recovery: rows whose lease expired without being released
     * (worker crashed, app restarted) get their claim wiped so the next
     * tick can re-claim them.
     */
    override fun reclaimExpired(now: OffsetDateTime): Int =
        ctx
            .update(AVAILABILITY_JOB)
            .set(AVAILABILITY_JOB.CLAIM_TOKEN, null as String?)
            .set(AVAILABILITY_JOB.CLAIMED_UNTIL, null as OffsetDateTime?)
            .set(AVAILABILITY_JOB.UPDATED_AT, now)
            .where(AVAILABILITY_JOB.CLAIMED_UNTIL.isNotNull)
            .and(AVAILABILITY_JOB.CLAIMED_UNTIL.lt(now))
            .execute()

    private fun JsonObject.toJSONB(): JSONB = JSONB.valueOf(json.encodeToString(JsonObject.serializer(), this))

    private fun fromRecord(r: Record): Job =
        Job(
            id = r.get(AVAILABILITY_JOB.ID)!!,
            watchId = r.get(AVAILABILITY_JOB.WATCH_ID)!!,
            intentPayload = json.parseToJsonElement(r.get(AVAILABILITY_JOB.INTENT_PAYLOAD)!!.data()).jsonObject,
            cadenceSec = r.get(AVAILABILITY_JOB.CADENCE_SEC)!!,
            status = r.get(AVAILABILITY_JOB.STATUS)!!,
            nextRunAt = r.get(AVAILABILITY_JOB.NEXT_RUN_AT)!!,
            claimedUntil = r.get(AVAILABILITY_JOB.CLAIMED_UNTIL),
            claimToken = r.get(AVAILABILITY_JOB.CLAIM_TOKEN),
            lastRunAt = r.get(AVAILABILITY_JOB.LAST_RUN_AT),
            createdAt = r.get(AVAILABILITY_JOB.CREATED_AT)!!,
            updatedAt = r.get(AVAILABILITY_JOB.UPDATED_AT)!!,
        )
}
