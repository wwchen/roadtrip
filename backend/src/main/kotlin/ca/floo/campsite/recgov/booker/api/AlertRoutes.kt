package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.poller.Poller
import ca.floo.campsite.recgov.booker.scheduler.Scheduler
import io.github.smiley4.ktorswaggerui.dsl.routing.delete
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.patch
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject

fun Route.alertRoutes(
    alerts: AlertRepo,
    poller: Poller?,
    scheduler: Scheduler? = null,
    bus: EventBus? = null,
    eventDriven: Boolean = false,
) {
    get("/api/campsite/alerts", {
        tags = listOf("campsite-alerts")
        summary = "Deprecated: list every recreation.gov alert (any status)"
        description = "Deprecated rec.gov-only alert path; prefer reservable availability monitor endpoints."
    }) {
        call.respondJson(alertDtos(alerts.list()))
    }

    post("/api/campsite/alerts", {
        tags = listOf("campsite-alerts")
        summary = "Deprecated: create a recreation.gov alert"
        description = "Deprecated rec.gov-only alert path; prefer POST /api/reservable/{rid}/availability/monitor."
    }) {
        val raw = call.receiveText().ifBlank { "{}" }
        val removedField = removedAlertField(raw)
        if (removedField != null) {
            return@post call.respondJson(
                ErrorDto("$removedField is no longer supported"),
                HttpStatusCode.BadRequest,
            )
        }
        val body = campsiteApiJson.decodeFromString<AlertCreateRequestDto>(raw)
        val campgroundId = body.campgroundId
        val campgroundName = body.campgroundName
        val startDate = body.startDate
        val endDate = body.endDate
        if (campgroundId == null || campgroundName == null || startDate == null || endDate == null) {
            return@post call.respondJson(
                ErrorDto("campground_id, campground_name, start_date, end_date are required"),
                HttpStatusCode.BadRequest,
            )
        }
        val id =
            alerts.create(
                AlertRepo.CreateInput(
                    campgroundId = campgroundId,
                    campgroundName = campgroundName,
                    parentName = body.parentName,
                    parentId = body.parentId,
                    startDate = startDate,
                    endDate = endDate,
                    campsiteTypes = body.campsiteTypes,
                    equipmentTypes = body.equipmentTypes,
                    maxPeople = body.maxPeople,
                    specificSites = body.specificSites,
                    notifySlack = body.notifySlack,
                    autoCart = body.autoCart,
                    stopAfterMatch = body.stopAfterMatch,
                    notes = body.notes,
                ),
            )
        scheduler?.upsertAlert(id)
        if (eventDriven && bus != null) {
            bus.publish(CampsiteEvent.UserPolledNow(alertId = id))
        } else {
            poller?.triggerNow()
        }
        call.respondJson(AlertCreatedDto(id))
    }

    patch("/api/campsite/alerts/{id}", {
        tags = listOf("campsite-alerts")
        summary = "Deprecated: patch a recreation.gov alert"
        description = "Deprecated rec.gov-only alert path; prefer reservable availability monitor endpoints."
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@patch call.respondJson(ErrorDto("bad id"), HttpStatusCode.BadRequest)
        val raw = call.receiveText().ifBlank { "{}" }
        val removedField = removedAlertField(raw)
        if (removedField != null) {
            return@patch call.respondJson(
                ErrorDto("$removedField is no longer supported"),
                HttpStatusCode.BadRequest,
            )
        }
        val body = campsiteApiJson.decodeFromString<AlertPatchRequestDto>(raw)
        val updates = mutableMapOf<String, Any?>()
        body.status?.let {
            if (it !in setOf("active", "paused", "done")) {
                return@patch call.respondJson(
                    ErrorDto("status must be active, paused, or done"),
                    HttpStatusCode.BadRequest,
                )
            }
            updates["status"] = it
        }
        body.startDate?.let { updates["start_date"] = it }
        body.endDate?.let { updates["end_date"] = it }
        body.maxPeople?.let { updates["max_people"] = it }
        body.campsiteTypes?.let { updates["campsite_types"] = it }
        body.equipmentTypes?.let { updates["equipment_types"] = it }
        body.specificSites?.let { updates["specific_sites"] = it }
        body.notifySlack?.let { updates["notify_slack"] = it }
        body.autoCart?.let { updates["auto_cart"] = it }
        body.stopAfterMatch?.let { updates["stop_after_match"] = it }
        alerts.patch(id, updates)
        // Status changes (active ↔ paused/done) require Scheduler to start/stop
        // the per-alert poll job. Calling upsertAlert is cheap and idempotent.
        if (updates.containsKey("status")) scheduler?.upsertAlert(id)
        call.respondJson(OkDto())
    }

    delete("/api/campsite/alerts/{id}", {
        tags = listOf("campsite-alerts")
        summary = "Deprecated: delete a recreation.gov alert"
        description = "Deprecated rec.gov-only alert path; prefer reservable availability monitor endpoints."
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respondJson(ErrorDto("bad id"), HttpStatusCode.BadRequest)
        alerts.delete(id)
        scheduler?.removeAlert(id)
        call.respondJson(OkDto())
    }
}

private fun removedAlertField(raw: String): String? =
    runCatching { campsiteApiJson.parseToJsonElement(raw).jsonObject }
        .getOrNull()
        ?.let { obj -> listOf("min_nights", "minNights").firstOrNull { it in obj } }
