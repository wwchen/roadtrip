package ca.floo.roadtrip

import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.ConfigSection
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private val mainLog = org.slf4j.LoggerFactory.getLogger("ca.floo.roadtrip.Main")

private const val MAIN_SERVER_PORT_KEY = "port"
private const val ADMIN_SERVER_PORT_KEY = "admin-port"
private const val LOG_SHUTDOWN_THREADS_KEY = "log-shutdown-threads"
private const val MAIN_SERVER_DEFAULT_PORT = 8080
private const val ADMIN_SERVER_DEFAULT_PORT = 8766
private const val ADMIN_SERVER_STOP_GRACE_MS = 1_000L
private const val ADMIN_SERVER_STOP_TIMEOUT_MS = 5_000L
private const val ENV_FLAG_TRUE = "true"

fun main() {
    val properties = ApplicationProperties.load()
    val config = ConfigSection(properties)
    installOptionalShutdownThreadDump(properties)

    val serverConfig = config.section("server")
    val port = serverConfig.value(MAIN_SERVER_PORT_KEY)?.toIntOrNull() ?: MAIN_SERVER_DEFAULT_PORT
    val adminPort =
        serverConfig.value(ADMIN_SERVER_PORT_KEY)?.toIntOrNull()
            ?: ADMIN_SERVER_DEFAULT_PORT

    val boot = createRoadtripBootContext(properties)
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

private fun installOptionalShutdownThreadDump(properties: Map<String, String>) {
    val diagnosticsConfig = ConfigSection(properties).section("roadtrip.diagnostics")
    if (diagnosticsConfig.value(LOG_SHUTDOWN_THREADS_KEY)?.equals(ENV_FLAG_TRUE, ignoreCase = true) != true) {
        return
    }

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

private object SharedBoot {
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
