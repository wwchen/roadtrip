package ca.floo.campsite.recgov.booker.db

import ca.floo.roadtrip.db.generated.tables.references.SCHEDULES
import org.jooq.DSLContext
import org.jooq.JSONB

/**
 * System schedule rows. One row per coroutine job the [Scheduler] runs;
 * each tick publishes its [eventType] on the [EventBus]. Per-alert poll
 * cadences live on `alerts.cadence_sec` instead of in this table —
 * see V3__campsite_schedules.sql for the rationale.
 */
data class Schedule(
    val id: Long,
    val name: String,
    val eventType: String,
    val payloadJson: String,
    val cadenceSec: Int,
    val enabled: Boolean,
)

class ScheduleRepo(
    private val ctx: DSLContext,
) {
    fun listEnabled(): List<Schedule> =
        ctx
            .selectFrom(SCHEDULES)
            .where(SCHEDULES.ENABLED.eq(true))
            .map { r ->
                Schedule(
                    id = r.id!!,
                    name = r.name!!,
                    eventType = r.eventType!!,
                    payloadJson = r.payloadJson?.data() ?: "{}",
                    cadenceSec = r.cadenceSec!!,
                    enabled = r.enabled!!,
                )
            }

    fun setEnabled(
        name: String,
        enabled: Boolean,
    ): Boolean =
        ctx
            .update(SCHEDULES)
            .set(SCHEDULES.ENABLED, enabled)
            .where(SCHEDULES.NAME.eq(name))
            .execute() > 0

    fun setCadence(
        name: String,
        cadenceSec: Int,
    ): Boolean =
        ctx
            .update(SCHEDULES)
            .set(SCHEDULES.CADENCE_SEC, cadenceSec)
            .where(SCHEDULES.NAME.eq(name))
            .execute() > 0

    fun setPayload(
        name: String,
        json: String,
    ): Boolean =
        ctx
            .update(SCHEDULES)
            .set(SCHEDULES.PAYLOAD_JSON, JSONB.valueOf(json))
            .where(SCHEDULES.NAME.eq(name))
            .execute() > 0
}
