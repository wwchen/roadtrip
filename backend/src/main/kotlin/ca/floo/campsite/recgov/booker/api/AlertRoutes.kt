package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.campsite.recgov.booker.poller.Poller
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Route.alertRoutes(
    alerts: AlertRepo,
    poller: Poller?,
) {
    get("/api/campsite/alerts") {
        call.respondText(JsonArray(alerts.list().map { alertJson(it) }).toString())
    }

    post("/api/campsite/alerts") {
        val body = parseJson(call.receiveText())
        val campgroundId = body.string("campground_id")
        val campgroundName = body.string("campground_name")
        val startDate = body.string("start_date")
        val endDate = body.string("end_date")
        if (campgroundId == null || campgroundName == null || startDate == null || endDate == null) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                """{"error":"campground_id, campground_name, start_date, end_date are required"}""",
            )
        }
        val id =
            alerts.create(
                AlertRepo.CreateInput(
                    campgroundId = campgroundId,
                    campgroundName = campgroundName,
                    parentName = body.string("parent_name"),
                    parentId = body.string("parent_id"),
                    startDate = startDate,
                    endDate = endDate,
                    minNights = body.int("min_nights") ?: 1,
                    campsiteTypes = body.stringList("campsite_types"),
                    equipmentTypes = body.stringList("equipment_types"),
                    maxPeople = body.int("max_people"),
                    specificSites = body.stringList("specific_sites"),
                    notifySlack = body.bool("notify_slack") ?: true,
                    autoCart = body.bool("auto_cart") ?: false,
                    stopAfterMatch = body.bool("stop_after_match") ?: true,
                    notes = body.string("notes"),
                ),
            )
        poller?.triggerNow()
        call.respondText("""{"id":$id}""")
    }

    patch("/api/campsite/alerts/{id}") {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, "bad id")
        val body = parseJson(call.receiveText())
        val updates = mutableMapOf<String, Any?>()
        body.string("status")?.let {
            if (it !in setOf("active", "paused", "done")) {
                return@patch call.respond(HttpStatusCode.BadRequest, """{"error":"status must be active, paused, or done"}""")
            }
            updates["status"] = it
        }
        body.string("start_date")?.let { updates["start_date"] = it }
        body.string("end_date")?.let { updates["end_date"] = it }
        body.int("min_nights")?.let { updates["min_nights"] = it }
        body.int("max_people")?.let { updates["max_people"] = it }
        body.array("campsite_types")?.let { updates["campsite_types"] = body.stringList("campsite_types") }
        body.array("equipment_types")?.let { updates["equipment_types"] = body.stringList("equipment_types") }
        body.array("specific_sites")?.let { updates["specific_sites"] = body.stringList("specific_sites") }
        body.bool("notify_slack")?.let { updates["notify_slack"] = it }
        body.bool("auto_cart")?.let { updates["auto_cart"] = it }
        body.bool("stop_after_match")?.let { updates["stop_after_match"] = it }
        alerts.patch(id, updates)
        call.respondText("""{"ok":true}""")
    }

    delete("/api/campsite/alerts/{id}") {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "bad id")
        alerts.delete(id)
        call.respondText("""{"ok":true}""")
    }
}

internal fun alertJson(a: Alert) =
    buildJsonObject {
        put("id", a.id)
        put("campground_id", a.campgroundId)
        put("campground_name", a.campgroundName)
        put("parent_name", a.parentName ?: "")
        put("parent_id", a.parentId ?: "")
        put("start_date", a.startDate)
        put("end_date", a.endDate)
        put("min_nights", a.minNights)
        put("campsite_types", JsonArray(a.campsiteTypes.map { JsonPrimitive(it) }))
        put("equipment_types", JsonArray(a.equipmentTypes.map { JsonPrimitive(it) }))
        put("max_people", a.maxPeople ?: 0)
        put("specific_sites", JsonArray(a.specificSites.map { JsonPrimitive(it) }))
        put("notify_slack", a.notifySlack)
        put("auto_cart", a.autoCart)
        put("stop_after_match", a.stopAfterMatch)
        put("status", a.status)
        put("last_checked", a.lastChecked ?: "")
        put("last_match", a.lastMatch ?: "")
        put("notes", a.notes ?: "")
        put("created_at", a.createdAt)
    }
