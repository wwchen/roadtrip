package ca.floo.roadtrip

import ca.floo.roadtrip.api.poiRoutes
import ca.floo.roadtrip.importer.DbConfig
import ca.floo.roadtrip.importer.dataSourceFor
import ca.floo.roadtrip.importer.dsl
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val ds = dataSourceFor(DbConfig.fromEnv())
    val ctx = dsl(ds)
    routing {
        poiRoutes(ctx)
    }
}
