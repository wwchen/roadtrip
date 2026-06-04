package ca.floo.roadtrip

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

// Phase 2.5 placeholder. The real /api/pois route lands in 2.8 (PoiRoutes.kt);
// for now this just proves the Ktor scaffold compiles and serves traffic.
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    routing {
        get("/api/pois/health") {
            call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json)
        }
    }
}
