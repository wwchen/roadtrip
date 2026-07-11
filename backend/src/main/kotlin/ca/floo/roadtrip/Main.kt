package ca.floo.roadtrip

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private val mainLog = org.slf4j.LoggerFactory.getLogger("ca.floo.roadtrip.Main")

private const val ENV_LOG_SHUTDOWN_THREADS = "ROADTRIP_LOG_SHUTDOWN_THREADS"
private const val MAIN_SERVER_DEFAULT_PORT = 8080
private const val ADMIN_SERVER_DEFAULT_PORT = 8766
private const val ADMIN_SERVER_STOP_GRACE_MS = 1_000L
private const val ADMIN_SERVER_STOP_TIMEOUT_MS = 5_000L
private const val ENV_FLAG_TRUE = "true"

fun main() {
    installOptionalShutdownThreadDump()

    val port = System.getenv("PORT")?.toIntOrNull() ?: MAIN_SERVER_DEFAULT_PORT
    val adminPort = System.getenv("ADMIN_PORT")?.toIntOrNull() ?: ADMIN_SERVER_DEFAULT_PORT

    val boot = createRoadtripBootContext()
    SharedBoot.instance = boot

    val adminServer =
        embeddedServer(Netty, port = adminPort, host = "0.0.0.0") {
            adminModule()
        }
    adminServer.start(wait = false)

    val mainServer =
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            module()
        }
    try {
        mainServer.start(wait = true)
        mainLog.info("main server start(wait=true) returned")
    } finally {
        runCatching {
            adminServer.stop(ADMIN_SERVER_STOP_GRACE_MS, ADMIN_SERVER_STOP_TIMEOUT_MS)
        }.onFailure { e ->
            mainLog.warn("admin server stop failed", e)
        }
    }
}

private fun installOptionalShutdownThreadDump() {
    if (System.getenv(ENV_LOG_SHUTDOWN_THREADS)?.equals(ENV_FLAG_TRUE, ignoreCase = true) != true) return

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
