package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.poller.Poller
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.pollRoutes(poller: Poller) {
    post("/api/campsite/poll") {
        poller.triggerNow()
        call.respondText("""{"ok":true,"message":"Poll triggered"}""")
    }
}
