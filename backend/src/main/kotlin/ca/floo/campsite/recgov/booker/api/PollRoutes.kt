package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.events.CampsiteEvent
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.poller.Poller
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.pollRoutes(
    poller: Poller,
    bus: EventBus? = null,
    eventDriven: Boolean = false,
) {
    post("/api/campsite/poll") {
        if (eventDriven && bus != null) {
            bus.publish(CampsiteEvent.UserPolledNow(alertId = null))
            call.respondText("""{"ok":true,"message":"Poll queued"}""")
        } else {
            poller.triggerNow()
            call.respondText("""{"ok":true,"message":"Poll triggered"}""")
        }
    }
}
