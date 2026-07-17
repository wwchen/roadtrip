package ca.floo.roadtrip

import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.ConfigSection
import ca.floo.roadtrip.di.infraModule
import ca.floo.roadtrip.di.registerKoinRoutes
import ca.floo.roadtrip.di.repoModule
import ca.floo.roadtrip.di.serviceModule
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import org.koin.ktor.plugin.Koin

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

fun Application.module() {
    install(Koin) {
        modules(infraModule(environment.config), repoModule, serviceModule)
    }
    val properties: Map<String, String> =
        ApplicationProperties.load(baseConfig = environment.config)
    installOptionalShutdownThreadDump(properties)
    installRoadtripPlugins()
    registerKoinRoutes()
}
