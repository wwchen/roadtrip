package ca.floo.roadtrip.route

import ca.floo.roadtrip.installRoadtripPlugins
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

/**
 * The route-test harness: the plugins a real request goes through, plus the
 * contract body check.
 *
 * [ContractBodyCheck] is installed after [installRoadtripPlugins], so its
 * Render-phase hook sees the text `ContentNegotiation` produced and sees it
 * before `Compression` touches it.
 */
internal fun Application.routeTestApplication(body: Route.() -> Unit) {
    installRoadtripPlugins()
    install(ContractBodyCheck)
    routing(body)
}
