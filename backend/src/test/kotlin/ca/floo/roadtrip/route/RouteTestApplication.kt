package ca.floo.roadtrip.route

import ca.floo.roadtrip.installRoadtripPlugins
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

internal fun Application.routeTestApplication(body: Route.() -> Unit) {
    installRoadtripPlugins()
    routing(body)
}
