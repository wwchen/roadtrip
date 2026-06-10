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
import io.ktor.server.routing.Route

fun Route.alertRoutes(
    alerts: AlertRepo,
    poller: Poller?,
    scheduler: Scheduler? = null,
    bus: EventBus? = null,
    eventDriven: Boolean = false,
) {
    get("/api/campsite/alerts", {
        tags = listOf("campsite-alerts")
        summary = "List every recreation.gov alert (any status)"
    }) {
        call.respondJson(alertDtos(alerts.list()))
    }

    post("/api/campsite/alerts", {
        tags = listOf("campsite-alerts")
        summary = "Create a new alert; triggers an immediate poll"
    }) {
        val body = call.receiveCampsiteJson<AlertCreateRequestDto>()
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
                    minNights = body.minNights,
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
        summary = "Patch one or more alert fields (status, dates, party size, …)"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@patch call.respondJson(ErrorDto("bad id"), HttpStatusCode.BadRequest)
        val body = call.receiveCampsiteJson<AlertPatchRequestDto>()
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
        body.minNights?.let { updates["min_nights"] = it }
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
        summary = "Hard-delete an alert and stop its poll job"
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respondJson(ErrorDto("bad id"), HttpStatusCode.BadRequest)
        alerts.delete(id)
        scheduler?.removeAlert(id)
        call.respondJson(OkDto())
    }
}
