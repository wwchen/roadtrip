package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.db.AlertRepo
import ca.floo.campsite.recgov.booker.db.MatchRepo
import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.events.matchFoundEventData
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route

fun Route.campsiteDebugRoutes(
    alerts: AlertRepo,
    matches: MatchRepo,
    bus: EventBus,
) {
    post("/api/admin/campsite/debug/synth-match", {
        tags = listOf("campsite-admin")
        summary = "Debug-only: create an alert + match in one shot for protocol harness tests"
    }) {
        val body = call.receiveCampsiteJson<DebugSynthMatchRequestDto>()
        val campgroundId = body.campgroundId ?: "232447"
        val campsiteId = body.campsiteId ?: "0"
        val startDate = body.startDate ?: "2026-04-28"
        val endDate = body.endDate ?: "2026-04-29"
        val alertId =
            alerts.create(
                AlertRepo.CreateInput(
                    campgroundId = campgroundId,
                    campgroundName = "spike-$campgroundId",
                    parentName = null,
                    parentId = null,
                    startDate = startDate,
                    endDate = endDate,
                    minNights = 1,
                    campsiteTypes = emptyList(),
                    equipmentTypes = emptyList(),
                    maxPeople = null,
                    specificSites = emptyList(),
                    notifySlack = false,
                    autoCart = false,
                    stopAfterMatch = false,
                    notes = "spike",
                ),
            )
        // Keep debug artifacts from being picked up by the real poller if a
        // browser smoke test is interrupted before cleanup runs.
        alerts.patch(alertId, mapOf("status" to "paused"))
        val matchId =
            matches.create(
                MatchRepo.CreateInput(
                    alertId = alertId,
                    campgroundId = campgroundId,
                    campsiteId = campsiteId,
                    site = "spike-site",
                    loop = null,
                    campsiteType = null,
                    availableDates = listOf(startDate),
                    firstDate = startDate,
                    nights = 1,
                ),
            ) ?: return@post call.respondJson(
                ErrorDto("duplicate match"),
                status = HttpStatusCode.Conflict,
            )
        val m = matches.get(matchId)!!
        bus.publish(CampsiteEvent.MatchFound(matchJson = matchFoundEventData(m)))
        call.respondJson(DebugSynthMatchDto(id = matchId))
    }
}
