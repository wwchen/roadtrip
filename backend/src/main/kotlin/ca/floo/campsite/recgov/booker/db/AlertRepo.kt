package ca.floo.campsite.recgov.booker.db

import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.roadtrip.db.generated.tables.references.ALERTS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import java.time.LocalDate
import java.time.OffsetDateTime

class AlertRepo(
    private val ctx: DSLContext,
) {
    fun list(): List<Alert> {
        // status order: active, paused, done, then created_at DESC
        val statusOrder = "CASE status WHEN 'active' THEN 0 WHEN 'paused' THEN 1 WHEN 'done' THEN 2 ELSE 9 END"
        return ctx
            .selectFrom(ALERTS)
            .orderBy(
                org.jooq.impl.DSL
                    .field(statusOrder),
                ALERTS.CREATED_AT.desc(),
            ).map { it.toDomain() }
    }

    fun listActive(): List<Alert> = ctx.selectFrom(ALERTS).where(ALERTS.STATUS.eq("active")).map { it.toDomain() }

    fun get(id: Long): Alert? =
        ctx
            .selectFrom(ALERTS)
            .where(ALERTS.ID.eq(id))
            .fetchOne()
            ?.toDomain()

    data class CreateInput(
        val campgroundId: String,
        val campgroundName: String,
        val parentName: String?,
        val parentId: String?,
        val startDate: String,
        val endDate: String,
        val minNights: Int,
        val campsiteTypes: List<String>,
        val equipmentTypes: List<String>,
        val maxPeople: Int?,
        val specificSites: List<String>,
        val notifySlack: Boolean,
        val autoCart: Boolean,
        val stopAfterMatch: Boolean,
        val notes: String?,
    )

    fun create(input: CreateInput): Long {
        val rec = ctx.newRecord(ALERTS)
        rec.campgroundId = input.campgroundId
        rec.campgroundName = input.campgroundName
        rec.parentName = input.parentName
        rec.parentId = input.parentId
        rec.startDate = LocalDate.parse(input.startDate)
        rec.endDate = LocalDate.parse(input.endDate)
        rec.minNights = input.minNights
        rec.campsiteTypes = jsonbList(input.campsiteTypes)
        rec.equipmentTypes = jsonbList(input.equipmentTypes)
        rec.maxPeople = input.maxPeople
        rec.specificSites = jsonbList(input.specificSites)
        rec.notifySlack = input.notifySlack
        rec.autoCart = input.autoCart
        rec.stopAfterMatch = input.stopAfterMatch
        rec.notes = input.notes
        rec.status = "active"
        rec.store()
        return rec.id!!
    }

    fun patch(
        id: Long,
        fields: Map<String, Any?>,
    ): Boolean {
        val rec = ctx.selectFrom(ALERTS).where(ALERTS.ID.eq(id)).fetchOne() ?: return false
        for ((k, v) in fields) {
            when (k) {
                "status" -> rec.status = v as String
                "start_date" -> rec.startDate = LocalDate.parse(v as String)
                "end_date" -> rec.endDate = LocalDate.parse(v as String)
                "min_nights" -> rec.minNights = (v as Number).toInt()
                "max_people" -> rec.maxPeople = (v as? Number)?.toInt()
                "campsite_types" -> rec.campsiteTypes = jsonbList(v as List<String>)
                "equipment_types" -> rec.equipmentTypes = jsonbList(v as List<String>)
                "specific_sites" -> rec.specificSites = jsonbList(v as List<String>)
                "notify_slack" -> rec.notifySlack = v as Boolean
                "auto_cart" -> rec.autoCart = v as Boolean
                "stop_after_match" -> rec.stopAfterMatch = v as Boolean
            }
        }
        return rec.store() > 0
    }

    fun delete(id: Long): Boolean = ctx.deleteFrom(ALERTS).where(ALERTS.ID.eq(id)).execute() > 0

    fun markChecked(
        id: Long,
        now: OffsetDateTime = OffsetDateTime.now(),
    ) {
        ctx
            .update(ALERTS)
            .set(ALERTS.LAST_CHECKED, now)
            .where(ALERTS.ID.eq(id))
            .execute()
    }

    fun markLastMatch(
        id: Long,
        at: OffsetDateTime = OffsetDateTime.now(),
    ) {
        ctx
            .update(ALERTS)
            .set(ALERTS.LAST_MATCH, at)
            .where(ALERTS.ID.eq(id))
            .execute()
    }
}

internal fun jsonbList(items: List<String>): JSONB =
    JSONB.valueOf(
        buildString {
            append('[')
            items.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            }
            append(']')
        },
    )

internal fun parseStringList(jsonb: JSONB?): List<String> {
    if (jsonb == null) return emptyList()
    return try {
        (Json.parseToJsonElement(jsonb.data()) as JsonArray).map { (it as JsonPrimitive).content }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun Record.toDomain(): Alert {
    val r = this as ca.floo.roadtrip.db.generated.tables.records.AlertsRecord
    return Alert(
        id = r.id!!,
        campgroundId = r.campgroundId!!,
        campgroundName = r.campgroundName ?: "",
        parentName = r.parentName,
        parentId = r.parentId,
        startDate = r.startDate.toString(),
        endDate = r.endDate.toString(),
        minNights = r.minNights ?: 1,
        campsiteTypes = parseStringList(r.campsiteTypes),
        equipmentTypes = parseStringList(r.equipmentTypes),
        maxPeople = r.maxPeople,
        specificSites = parseStringList(r.specificSites),
        notifySlack = r.notifySlack ?: true,
        autoCart = r.autoCart ?: false,
        stopAfterMatch = r.stopAfterMatch ?: true,
        status = r.status ?: "active",
        lastChecked = r.lastChecked?.toString(),
        lastMatch = r.lastMatch?.toString(),
        notes = r.notes,
        createdAt = r.createdAt!!.toString(),
    )
}
