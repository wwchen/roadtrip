package ca.floo.roadtrip

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private val mainLog = org.slf4j.LoggerFactory.getLogger("ca.floo.roadtrip.Main")

fun main() {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            mainLog.info("JVM shutdown hook fired")
            Thread.getAllStackTraces().forEach { (thread, stack) ->
                if (stack.isNotEmpty()) {
                    mainLog.info(
                        "thread={} state={} stack={}",
                        thread.name,
                        thread.state,
                        stack.joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" },
                    )
                }
            }
        },
    )

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
    mainLog.info("main server start(wait=true) returned — JVM will exit")
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
