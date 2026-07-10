package ca.floo.roadtrip

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val adminPort = System.getenv("ADMIN_PORT")?.toIntOrNull() ?: 8766

    val boot = createRoadtripBootContext()
    SharedBoot.instance = boot

    val adminServer =
        embeddedServer(Netty, port = adminPort, host = "0.0.0.0") {
            adminModule()
        }
    adminServer.start(wait = false)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

internal object SharedBoot {
    lateinit var instance: RoadtripBootContext
}

internal fun includeInRoadtripOpenApi(path: List<String>): Boolean = path.firstOrNull() == "api" && path.getOrNull(1) != "docs"

fun Application.module() {
    val boot = SharedBoot.instance
    installRoadtripPlugins()
    val runtime = startRoadtripRuntime(boot)
    environment.monitor.subscribe(ApplicationStopping) {
        runtime.close()
    }
    registerRoadtripRoutes(runtime)
}

fun Application.adminModule() {
    val boot = SharedBoot.instance
    installAdminPlugins()
    registerAdminRoutes(boot)
}
