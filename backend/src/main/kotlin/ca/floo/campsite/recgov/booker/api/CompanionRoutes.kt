package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.CompanionRegistry
import ca.floo.campsite.recgov.booker.events.EventBus
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route

private val jsonStringRe = Regex("\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"")

private fun parseField(
    body: String,
    key: String,
): String? =
    jsonStringRe
        .findAll(body)
        .firstOrNull { it.groupValues[1] == key }
        ?.groupValues
        ?.get(2)

fun Route.companionRoutes(
    companions: CompanionRegistry,
    bus: EventBus,
) {
    post("/api/campsite/companion/heartbeat", {
        tags = listOf("campsite-companion")
        summary = "Heartbeat from a Playwright companion; flips offline → online"
    }) {
        val body = call.receiveText()
        val id =
            parseField(body, "companion_id")
                ?: return@post call.respond(HttpStatusCode.BadRequest, "missing companion_id")
        val cameBack = companions.heartbeat(id)
        if (cameBack) {
            bus.publish(CampsiteEvent.CompanionOnline(companionId = id))
        }
        call.respondText("""{"ok":true}""")
    }

    get("/api/campsite/companion/status", {
        tags = listOf("campsite-companion")
        summary = "List registered companions and their last-seen / offline state"
    }) {
        val list =
            companions.status().joinToString(",") {
                """{"id":"${it.id}","lastSeen":"${it.lastSeen}","offline":${it.offline}}"""
            }
        call.respondText("""{"companions":[$list]}""")
    }
}
