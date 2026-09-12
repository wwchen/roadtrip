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
 *
 * [fixture] belongs to `ContractBodyCheckTest` alone — the one suite that serves
 * drifted bodies on purpose. Its violations are then recorded under their own
 * ledger kind, so the ledger gate fails on every real one with nothing exempted.
 */
internal fun Application.routeTestApplication(
    fixture: Boolean = false,
    body: Route.() -> Unit,
) {
    installRoadtripPlugins()
    install(ContractBodyCheck) { this.fixture = fixture }
    routing(body)
}
