package ca.floo.campsite.recgov.booker.tools

import ca.floo.campsite.recgov.booker.db.jsonbList
import ca.floo.roadtrip.db.generated.tables.references.ALERTS
import ca.floo.roadtrip.db.generated.tables.references.MATCHES
import ca.floo.roadtrip.db.generated.tables.references.SETTINGS
import ca.floo.roadtrip.importer.DbConfig
import ca.floo.roadtrip.importer.dataSourceFor
import ca.floo.roadtrip.importer.dsl
import ca.floo.roadtrip.importer.migrate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.jooq.DSLContext
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.system.exitProcess

// Skip secrets that belong on the companion, not the backend.
private val SETTING_SKIP =
    setOf(
        "recgov_token",
        "recgov_cookies",
        "recgov_refresh_creds",
        "recgov_recaccount",
    )

fun main(args: Array<String>) {
    val path =
        args.firstOrNull() ?: run {
            System.err.println("usage: migrate <path/to/data.json>")
            exitProcess(2)
        }
    val file = File(path)
    if (!file.isFile) {
        System.err.println("not a file: $path")
        exitProcess(2)
    }

    val root = Json.parseToJsonElement(file.readText()).jsonObject
    val ds = dataSourceFor(DbConfig.fromEnv())
    migrate(ds)
    val ctx = dsl(ds)

    val settingsCount = importSettings(ctx, root["settings"] as? JsonObject)
    val (alerts, idMap) = importAlerts(ctx, root["alerts"] as? JsonArray)
    val matches = importMatches(ctx, root["matches"] as? JsonArray, idMap)

    println("Imported: settings=$settingsCount alerts=$alerts matches=$matches")
}

private fun importSettings(
    ctx: DSLContext,
    settings: JsonObject?,
): Int {
    if (settings == null) return 0
    var n = 0
    for ((k, v) in settings) {
        if (k in SETTING_SKIP) continue
        val s = (v as? JsonPrimitive)?.contentOrNull ?: continue
        ctx
            .insertInto(SETTINGS)
            .set(SETTINGS.KEY, k)
            .set(SETTINGS.VALUE, s)
            .onConflict(SETTINGS.KEY)
            .doUpdate()
            .set(SETTINGS.VALUE, s)
            .execute()
        n++
    }
    return n
}

/** Returns (count, legacyId → newId). */
private fun importAlerts(
    ctx: DSLContext,
    alerts: JsonArray?,
): Pair<Int, Map<Long, Long>> {
    if (alerts == null) return 0 to emptyMap()
    val idMap = mutableMapOf<Long, Long>()
    var n = 0
    for (a in alerts.filterIsInstance<JsonObject>()) {
        val legacyId = a.long("id") ?: continue
        val rec = ctx.newRecord(ALERTS)
        rec.campgroundId = a.string("campground_id") ?: continue
        rec.campgroundName = a.string("campground_name") ?: ""
        rec.parentName = a.string("parent_name")
        rec.parentId = a.string("parent_id")
        rec.startDate = LocalDate.parse(a.string("start_date") ?: continue)
        rec.endDate = LocalDate.parse(a.string("end_date") ?: continue)
        rec.minNights = a.int("min_nights") ?: 1
        rec.campsiteTypes = jsonbList(a.stringList("campsite_types"))
        rec.equipmentTypes = jsonbList(a.stringList("equipment_types"))
        rec.maxPeople = a.int("max_people")
        rec.specificSites = jsonbList(a.stringList("specific_sites"))
        rec.notifySlack = a.bool("notify_slack") ?: true
        rec.autoCart = a.bool("auto_cart") ?: false
        rec.stopAfterMatch = a.bool("stop_after_match") ?: true
        rec.status = a.string("status") ?: "active"
        rec.lastChecked = a.offsetDateTime("last_checked")
        rec.lastMatch = a.offsetDateTime("last_match")
        rec.notes = a.string("notes")
        a.offsetDateTime("created_at")?.let { rec.createdAt = it }
        rec.store()
        idMap[legacyId] = rec.id!!
        n++
    }
    return n to idMap
}

private fun importMatches(
    ctx: DSLContext,
    matches: JsonArray?,
    idMap: Map<Long, Long>,
): Int {
    if (matches == null) return 0
    var n = 0
    for (m in matches.filterIsInstance<JsonObject>()) {
        val legacyAlertId = m.long("alert_id") ?: continue
        val newAlertId = idMap[legacyAlertId] ?: continue // alert was filtered/missing
        val rec = ctx.newRecord(MATCHES)
        rec.alertId = newAlertId
        rec.campgroundId = m.string("campground_id") ?: continue
        rec.campsiteId = m.string("campsite_id") ?: continue
        rec.campsiteSite = m.string("campsite_site")
        rec.campsiteLoop = m.string("campsite_loop")
        rec.campsiteType = m.string("campsite_type")
        rec.availableDates = jsonbList(m.stringList("available_dates"))
        rec.firstDate = LocalDate.parse(m.string("first_date") ?: continue)
        rec.nights = m.int("nights") ?: 1
        m.offsetDateTime("found_at")?.let { rec.foundAt = it }
        rec.notified = m.bool("notified") ?: false
        rec.cartAdded = m.bool("cart_added")
        // Legacy `dismissed: true` becomes a dismissed_at timestamp.
        if (m.bool("dismissed") == true) {
            rec.dismissedAt = m.offsetDateTime("found_at") ?: OffsetDateTime.now()
        }
        rec.store()
        n++
    }
    return n
}

private fun JsonObject.string(k: String): String? =
    (this[k] as? JsonPrimitive)
        ?.takeIf { !it.isString || it.contentOrNull != null }
        ?.contentOrNull
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringList(k: String): List<String> =
    (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private fun JsonObject.offsetDateTime(k: String): OffsetDateTime? {
    val s = string(k) ?: return null
    return runCatching { OffsetDateTime.parse(s) }.getOrNull()
}
