package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.CompanionRegistry
import ca.floo.campsite.recgov.booker.events.EventBus
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route

fun Route.companionRoutes(
    companions: CompanionRegistry,
    bus: EventBus,
) {
    post("/api/campsite/companion/heartbeat", {
        tags = listOf("campsite-companion")
        summary = "Heartbeat from a Playwright companion; flips offline → online"
    }) {
        val body = call.receiveCampsiteJson<CompanionHeartbeatRequestDto>()
        val id =
            body.companionId
                ?: return@post call.respondJson(ErrorDto("missing companion_id"), HttpStatusCode.BadRequest)
        val cameBack = companions.heartbeat(id)
        if (cameBack) {
            bus.publish(CampsiteEvent.CompanionOnline(companionId = id))
        }
        call.respondJson(OkDto())
    }

    get("/api/campsite/companion/status", {
        tags = listOf("campsite-companion")
        summary = "List registered companions and their last-seen / offline state"
    }) {
        call.respondJson(
            CompanionStatusDto(
                companions =
                    companions.status().map {
                        CompanionStatusItemDto(
                            id = it.id,
                            lastSeen = it.lastSeen.toString(),
                            offline = it.offline,
                        )
                    },
            ),
        )
    }
}
