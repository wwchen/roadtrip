package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityWatch.Companion.AVAILABILITY_WATCH
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500

class AvailabilityWatchRepo(
    private val ctx: DSLContext,
) {
    private val reservablesRepo = ReservableRepo(ctx)
    private val json = Json

    data class CreateInput(
        val poiId: Long?,
        val reservableId: Long?,
        val reservableFilters: JsonObject,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val cadenceSec: Int,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
    )

    data class UpdateInput(
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
        val poiId: Long?,
        val reservableId: Long?,
        val reservable: Reservable?,
        val reservableFilters: JsonObject,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val cadenceSec: Int,
        val triggerKinds: List<String>,
        val triggerConfig: JsonObject,
        val stopWhenTriggered: Boolean,
        val status: WatchStatus,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    )

    fun create(input: CreateInput): Watch {
        require((input.poiId == null) xor (input.reservableId == null)) {
            "exactly one of poiId/reservableId must be set"
        }
        val id =
            ctx
                .insertInto(AVAILABILITY_WATCH)
                .set(AVAILABILITY_WATCH.POI_ID, input.poiId)
                .set(AVAILABILITY_WATCH.RESERVABLE_ID, input.reservableId)
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
        return findById(id)!!
    }

    fun findById(id: Long): Watch? = baseSelect().where(AVAILABILITY_WATCH.ID.eq(id)).fetchOne()?.let(::fromRecord)

    fun list(
        status: WatchStatus? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): List<Watch> {
        val effectiveLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_WATCH.STATUS.eq(status.wireValue)
        if (poiId != null) conds += AVAILABILITY_WATCH.POI_ID.eq(poiId)
        if (reservableId != null) conds += AVAILABILITY_WATCH.RESERVABLE_ID.eq(reservableId)
        return baseSelect()
            .where(if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds))
            .orderBy(AVAILABILITY_WATCH.CREATED_AT.desc(), AVAILABILITY_WATCH.ID.desc())
            .limit(effectiveLimit)
            .offset(offset)
            .fetch { fromRecord(it) }
    }

    fun count(
        status: WatchStatus? = null,
        poiId: Long? = null,
        reservableId: Long? = null,
    ): Int {
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_WATCH.STATUS.eq(status.wireValue)
        if (poiId != null) conds += AVAILABILITY_WATCH.POI_ID.eq(poiId)
        if (reservableId != null) conds += AVAILABILITY_WATCH.RESERVABLE_ID.eq(reservableId)
        return ctx
            .selectCount()
            .from(AVAILABILITY_WATCH)
            .where(if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds))
            .fetchOne(0, Int::class.java) ?: 0
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
        if (input.status != null) {
            query = query.set(AVAILABILITY_WATCH.STATUS, input.status.wireValue)
        }
        val rows = query.where(AVAILABILITY_WATCH.ID.eq(id)).execute()
        if (rows == 0) return null
        return findById(id)
    }

    fun delete(id: Long): Boolean = ctx.deleteFrom(AVAILABILITY_WATCH).where(AVAILABILITY_WATCH.ID.eq(id)).execute() > 0

    private fun baseSelect() =
        ctx
            .select(AVAILABILITY_WATCH.fields().toList() + RESERVABLES.fields().toList())
            .from(AVAILABILITY_WATCH)
            .leftJoin(RESERVABLES)
            .on(RESERVABLES.ID.eq(AVAILABILITY_WATCH.RESERVABLE_ID))

    private fun fromRecord(r: Record): Watch {
        val reservableId = r.get(AVAILABILITY_WATCH.RESERVABLE_ID)
        return Watch(
            id = r.get(AVAILABILITY_WATCH.ID)!!,
            poiId = r.get(AVAILABILITY_WATCH.POI_ID),
            reservableId = reservableId,
            reservable = if (reservableId != null) reservablesRepo.fromRecord(r) else null,
            reservableFilters = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.RESERVABLE_FILTERS)!!.data()).jsonObject,
            startDate = r.get(AVAILABILITY_WATCH.START_DATE)!!,
            endDate = r.get(AVAILABILITY_WATCH.END_DATE)!!,
            cadenceSec = r.get(AVAILABILITY_WATCH.CADENCE_SEC)!!,
            triggerKinds = r.get(AVAILABILITY_WATCH.TRIGGER_KINDS)!!.filterNotNull(),
            triggerConfig = json.parseToJsonElement(r.get(AVAILABILITY_WATCH.TRIGGER_CONFIG)!!.data()).jsonObject,
            stopWhenTriggered = r.get(AVAILABILITY_WATCH.STOP_WHEN_TRIGGERED)!!,
            status = WatchStatus.parse(r.get(AVAILABILITY_WATCH.STATUS)!!) ?: error("invalid watch status"),
            createdAt = r.get(AVAILABILITY_WATCH.CREATED_AT)!!,
            updatedAt = r.get(AVAILABILITY_WATCH.UPDATED_AT)!!,
        )
    }
}
