package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ReservableAvailabilityMonitors.Companion.RESERVABLE_AVAILABILITY_MONITORS
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.models.Reservable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import java.time.OffsetDateTime

class ReservableAvailabilityMonitorRepo(
    private val ctx: DSLContext,
) {
    private val reservables = ReservableRepo(ctx)
    private val json = Json

    data class CreateInput(
        val cadenceSec: Int,
        val triggerActions: JsonArray,
        val stopWhenTriggered: Boolean,
    )

    data class Monitor(
        val id: Long,
        val reservable: Reservable,
        val cadenceSec: Int,
        val triggerActions: JsonArray,
        val stopWhenTriggered: Boolean,
        val status: String,
        val lastCheckedAt: OffsetDateTime?,
        val lastTriggeredAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    )

    fun create(
        reservableId: Long,
        input: CreateInput,
    ): Monitor {
        val id =
            ctx
                .insertInto(RESERVABLE_AVAILABILITY_MONITORS)
                .set(RESERVABLE_AVAILABILITY_MONITORS.RESERVABLE_ID, reservableId)
                .set(RESERVABLE_AVAILABILITY_MONITORS.CADENCE_SEC, input.cadenceSec)
                .set(RESERVABLE_AVAILABILITY_MONITORS.TRIGGER_ACTIONS, JSONB.valueOf(json.encodeToString(input.triggerActions)))
                .set(RESERVABLE_AVAILABILITY_MONITORS.STOP_WHEN_TRIGGERED, input.stopWhenTriggered)
                .returningResult(RESERVABLE_AVAILABILITY_MONITORS.ID)
                .fetchOne()!!
                .value1()!!
        return findById(id)!!
    }

    fun list(): List<Monitor> =
        ctx
            .select((RESERVABLE_AVAILABILITY_MONITORS.fields().toList() + RESERVABLES.fields().toList()))
            .from(RESERVABLE_AVAILABILITY_MONITORS)
            .join(RESERVABLES)
            .on(RESERVABLES.ID.eq(RESERVABLE_AVAILABILITY_MONITORS.RESERVABLE_ID))
            .orderBy(
                RESERVABLE_AVAILABILITY_MONITORS.CREATED_AT.desc(),
                RESERVABLE_AVAILABILITY_MONITORS.ID.desc(),
            ).fetch { fromRecord(it) }

    private fun findById(id: Long): Monitor? =
        ctx
            .select((RESERVABLE_AVAILABILITY_MONITORS.fields().toList() + RESERVABLES.fields().toList()))
            .from(RESERVABLE_AVAILABILITY_MONITORS)
            .join(RESERVABLES)
            .on(RESERVABLES.ID.eq(RESERVABLE_AVAILABILITY_MONITORS.RESERVABLE_ID))
            .where(RESERVABLE_AVAILABILITY_MONITORS.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    private fun fromRecord(r: Record): Monitor =
        Monitor(
            id = r.get(RESERVABLE_AVAILABILITY_MONITORS.ID)!!,
            reservable = reservables.fromRecord(r),
            cadenceSec = r.get(RESERVABLE_AVAILABILITY_MONITORS.CADENCE_SEC)!!,
            triggerActions = json.parseToJsonElement(r.get(RESERVABLE_AVAILABILITY_MONITORS.TRIGGER_ACTIONS)!!.data()).jsonArray,
            stopWhenTriggered = r.get(RESERVABLE_AVAILABILITY_MONITORS.STOP_WHEN_TRIGGERED)!!,
            status = r.get(RESERVABLE_AVAILABILITY_MONITORS.STATUS)!!,
            lastCheckedAt = r.get(RESERVABLE_AVAILABILITY_MONITORS.LAST_CHECKED_AT),
            lastTriggeredAt = r.get(RESERVABLE_AVAILABILITY_MONITORS.LAST_TRIGGERED_AT),
            createdAt = r.get(RESERVABLE_AVAILABILITY_MONITORS.CREATED_AT)!!,
            updatedAt = r.get(RESERVABLE_AVAILABILITY_MONITORS.UPDATED_AT)!!,
        )
}
