package ca.floo.roadtrip

import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.ConfigSection
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

private val mainLog = org.slf4j.LoggerFactory.getLogger("ca.floo.roadtrip.Main")

private const val LOG_SHUTDOWN_THREADS_KEY = "log-shutdown-threads"
private const val ENV_FLAG_TRUE = "true"

fun main(args: Array<String>): Unit = EngineMain.main(args)

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

internal fun includeInRoadtripOpenApi(path: List<String>): Boolean =
    (path.firstOrNull() == "api" && path.getOrNull(1) != "docs") ||
        path.firstOrNull() == "test"

fun Application.module() {
    val properties = ApplicationProperties.load(baseConfig = environment.config)
    installOptionalShutdownThreadDump(properties)
    val boot = createRoadtripBootContext(properties)

    installRoadtripDependencies(boot)
    installRoadtripPlugins()
    registerRoadtripRoutes()
}
