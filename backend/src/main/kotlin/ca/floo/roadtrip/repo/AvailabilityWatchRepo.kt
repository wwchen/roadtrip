package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityRun.Companion.AVAILABILITY_RUN
import ca.floo.roadtrip.db.generated.tables.AvailabilityWatch.Companion.AVAILABILITY_WATCH
import ca.floo.roadtrip.db.generated.tables.AvailabilityWatchPoller.Companion.AVAILABILITY_WATCH_POLLER
import ca.floo.roadtrip.db.generated.tables.AvailabilityWatchTarget.Companion.AVAILABILITY_WATCH_TARGET
import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectField
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500

class AvailabilityWatchRepo(
    private val ctx: DSLContext,
) {
    private val targetsRepo = AvailabilityWatchTargetRepo(ctx)
    private val json = Json

    data class CreateInput(
        val targets: List<AvailabilityWatchTargetRepo.TargetInput>,
        val reservableFilters: JsonObject,
        val startDate: LocalDate,
        val endDate: LocalDate,
        // NULL = no watch-level cadence override (fall through to POI override / default).
        val cadenceSec: Int?,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
    )

    data class UpdateInput(
        val targets: List<AvailabilityWatchTargetRepo.TargetInput>? = null,
        val reservableFilters: JsonObject? = null,
        val startDate: LocalDate? = null,
        val endDate: LocalDate? = null,
        val cadenceSec: Int? = null,
        val triggerKinds: List<String>? = null,
        val triggerConfig: JsonObject? = null,
        val stopWhenTriggered: Boolean? = null,
        val status: WatchStatus? = null,
    )

    data class Watch(
        val id: Long,
        val targets: List<AvailabilityWatchTargetRepo.WatchTarget>,
        val reservableFilters: JsonObject,
        val startDate: LocalDate,
        val endDate: LocalDate,
        // NULL = no watch-level cadence override; resolver falls through.
        val cadenceSec: Int?,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
        val status: WatchStatus,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
        // Latest poll run across this watch's poller(s); null when never run.
        // Populated by list()/findById(); left null by fromRecord() so sibling
        // repos that reuse the row mapping are unaffected.
        val lastRun: LatestRun? = null,
    )

    /**
     * Freshness/error snapshot of the most recent [AVAILABILITY_RUN] across a
     * watch's poller(s). `completedAt` is null while a run is still in flight.
     */
    data class LatestRun(
        val status: String,
        val error: String?,
        val completedAt: OffsetDateTime?,
    )

    fun create(input: CreateInput): Watch {
        require(input.targets.isNotEmpty()) { "a watch must have at least one target" }
        val id =
            ctx
                .insertInto(AVAILABILITY_WATCH)
                .set(
                    AVAILABILITY_WATCH.RESERVABLE_FILTERS,
                    JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.reservableFilters)),
                ).set(AVAILABILITY_WATCH.START_DATE, input.startDate)
                .set(AVAILABILITY_WATCH.END_DATE, input.endDate)
                .set(AVAILABILITY_WATCH.CADENCE_SEC, input.cadenceSec)
                .set(AVAILABILITY_WATCH.TRIGGER_KINDS, input.triggerKinds.toTypedArray())
                .set(AVAILABILITY_WATCH.TRIGGER_CONFIG, JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.triggerConfig)))
                .set(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED, input.stopWhenTriggered)
                .returningResult(AVAILABILITY_WATCH.ID)
                .fetchOne()!!
                .value1()!!
        targetsRepo.replaceForWatch(id, input.targets)
        return findById(id)!!
    }

    fun findById(id: Long): Watch? =
        baseSelect()
            .where(AVAILABILITY_WATCH.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)
            ?.let { withLatestRuns(listOf(it)).single() }

    fun list(
        status: WatchStatus? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): List<Watch> {
        val effectiveLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val rows =
            baseSelect()
                .where(scopeConditions(status, poiId, reservableId))
                .orderBy(AVAILABILITY_WATCH.CREATED_AT.desc(), AVAILABILITY_WATCH.ID.desc())
                .limit(effectiveLimit)
                .offset(offset)
                .fetch { fromRecord(it) }
        return withLatestRuns(rows)
    }

    /**
     * Attaches the latest poll run to each watch in one batched query, so
     * list() stays a single extra round-trip regardless of page size (no
     * per-watch fan-out). Watches with no run keep [Watch.lastRun] = null.
     */
    private fun withLatestRuns(watches: List<Watch>): List<Watch> {
        if (watches.isEmpty()) return watches
        val runs = latestRunsByWatch(watches.map { it.id })
        return watches.map { w -> runs[w.id]?.let { w.copy(lastRun = it) } ?: w }
    }

    /**
     * Newest run per watch across all its pollers. Uses Postgres `DISTINCT ON
     * (watch_id)` ordered by run start desc, so exactly one row (the most
     * recent) survives per watch. Backed by
     * `availability_run_poller_started_idx (poller_id, started_at DESC)`.
     */
    private fun latestRunsByWatch(watchIds: List<Long>): Map<Long, LatestRun> {
        if (watchIds.isEmpty()) return emptyMap()
        return ctx
            .select(
                AVAILABILITY_WATCH_POLLER.WATCH_ID,
                AVAILABILITY_RUN.STATUS,
                AVAILABILITY_RUN.ERROR,
                AVAILABILITY_RUN.COMPLETED_AT,
            ).distinctOn(AVAILABILITY_WATCH_POLLER.WATCH_ID)
            .from(AVAILABILITY_WATCH_POLLER)
            .join(AVAILABILITY_RUN)
            .on(AVAILABILITY_RUN.POLLER_ID.eq(AVAILABILITY_WATCH_POLLER.POLLER_ID))
            .where(AVAILABILITY_WATCH_POLLER.WATCH_ID.`in`(watchIds))
            .orderBy(AVAILABILITY_WATCH_POLLER.WATCH_ID, AVAILABILITY_RUN.STARTED_AT.desc())
            .fetch()
            .associate { r ->
                r.get(AVAILABILITY_WATCH_POLLER.WATCH_ID)!! to
                    LatestRun(
                        status = r.get(AVAILABILITY_RUN.STATUS)!!,
                        error = r.get(AVAILABILITY_RUN.ERROR),
                        completedAt = r.get(AVAILABILITY_RUN.COMPLETED_AT),
                    )
            }
    }

    fun count(
        status: WatchStatus? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
    ): Int =
        ctx
            .selectCount()
            .from(AVAILABILITY_WATCH)
            .where(scopeConditions(status, poiId, reservableId))
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * `poiId`/`reservableId` filters now match "watch's target set contains
     * this poi/reservable" rather than a single-column equality, since a
     * watch can have multiple targets. Modeled as an EXISTS subquery against
     * `availability_watch_target` rather than a join, so filtering never
     * duplicates a watch row when it has multiple matching targets.
     */
    private fun scopeConditions(
        status: WatchStatus?,
        poiId: Long?,
        reservableId: Long?,
    ): org.jooq.Condition {
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_WATCH.STATUS.eq(status.wireValue)
        if (poiId != null) {
            conds +=
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(AVAILABILITY_WATCH_TARGET)
                        .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(AVAILABILITY_WATCH.ID))
                        .and(AVAILABILITY_WATCH_TARGET.POI_ID.eq(poiId)),
                )
        }
        if (reservableId != null) {
            conds +=
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(AVAILABILITY_WATCH_TARGET)
                        .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(AVAILABILITY_WATCH.ID))
                        .and(AVAILABILITY_WATCH_TARGET.RESERVABLE_ID.eq(reservableId)),
                )
        }
        return if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds)
    }

    fun update(
        id: Long,
        input: UpdateInput,
    ): Watch? {
        var query = ctx.update(AVAILABILITY_WATCH).set(AVAILABILITY_WATCH.UPDATED_AT, OffsetDateTime.now())
        if (input.reservableFilters != null) {
            query =
                query.set(
                    AVAILABILITY_WATCH.RESERVABLE_FILTERS,
                    JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.reservableFilters)),
                )
        }
        if (input.startDate != null) query = query.set(AVAILABILITY_WATCH.START_DATE, input.startDate)
        if (input.endDate != null) query = query.set(AVAILABILITY_WATCH.END_DATE, input.endDate)
        if (input.cadenceSec != null) query = query.set(AVAILABILITY_WATCH.CADENCE_SEC, input.cadenceSec)
        if (input.triggerKinds != null) query = query.set(AVAILABILITY_WATCH.TRIGGER_KINDS, input.triggerKinds.toTypedArray())
        if (input.triggerConfig != null) {
            query =
                query.set(
                    AVAILABILITY_WATCH.TRIGGER_CONFIG,
                    JSONB.valueOf(json.encodeToString(JsonObject.serializer(), input.triggerConfig)),
                )
        }
        if (input.stopWhenTriggered != null) query = query.set(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED, input.stopWhenTriggered)
        if (input.status != null) query = query.set(AVAILABILITY_WATCH.STATUS, input.status.wireValue)
        val rows = query.where(AVAILABILITY_WATCH.ID.eq(id)).execute()
        if (rows == 0) return null
        if (input.targets != null) targetsRepo.replaceForWatch(id, input.targets)
        return findById(id)
    }

    fun delete(id: Long): Boolean = ctx.deleteFrom(AVAILABILITY_WATCH).where(AVAILABILITY_WATCH.ID.eq(id)).execute() > 0

    private fun baseSelect() = ctx.select(AVAILABILITY_WATCH.fields().toList()).from(AVAILABILITY_WATCH)

    /**
     * Exposed so sibling repos (e.g. [AvailabilityPollerRepo]) can extend
     * this select with their own conditions rather than re-deriving the
     * watch row mapping. Targets are no longer part of the base select (they
     * are N rows per watch); [fromRecord] loads them via a second query.
     */
    internal fun baseSelectFields(): List<SelectField<*>> = AVAILABILITY_WATCH.fields().toList()

    internal fun fromRecord(r: Record): Watch =
        Watch(
            id = r.get(AVAILABILITY_WATCH.ID)!!,
            targets = targetsRepo.listForWatch(r.get(AVAILABILITY_WATCH.ID)!!),
            reservableFilters = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.RESERVABLE_FILTERS)!!.data()).jsonObject,
            startDate = r.get(AVAILABILITY_WATCH.START_DATE)!!,
            endDate = r.get(AVAILABILITY_WATCH.END_DATE)!!,
            cadenceSec = r.get(AVAILABILITY_WATCH.CADENCE_SEC),
            triggerKinds = r.get(AVAILABILITY_WATCH.TRIGGER_KINDS)!!.filterNotNull(),
            triggerConfig = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.TRIGGER_CONFIG)!!.data()).jsonObject,
            stopWhenTriggered = r.get(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED)!!,
            status = WatchStatus.parse(r.get(AVAILABILITY_WATCH.STATUS)!!) ?: error("invalid watch status"),
            createdAt = r.get(AVAILABILITY_WATCH.CREATED_AT)!!,
            updatedAt = r.get(AVAILABILITY_WATCH.UPDATED_AT)!!,
        )
}
