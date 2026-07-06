package ca.floo.roadtrip

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

internal fun includeInRoadtripOpenApi(path: List<String>): Boolean = path.firstOrNull() == "api" && path.getOrNull(1) != "docs"

fun Application.module() {
    val boot = createRoadtripBootContext()
    installRoadtripPlugins()
    val runtime = startRoadtripRuntime(boot)
    environment.monitor.subscribe(ApplicationStopping) {
        runtime.close()
    }
    registerRoadtripRoutes(runtime)
}
