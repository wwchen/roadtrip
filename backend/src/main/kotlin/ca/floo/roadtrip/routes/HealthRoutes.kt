package ca.floo.roadtrip.routes

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import java.io.File
import java.time.Instant

// One-shot status JSON. Useful from the phone on the trip to confirm the
// supercharger detail cache is warm. Counts tesla-locations directories
// — the per-slug capture cache that feeds the supercharger row's name,
// stalls, kW, and pricing.
fun Route.healthRoutes(rawDir: File) {
    get("/api/health", {
        tags = listOf("health")
        summary = "Liveness probe + supercharger detail cache size"
        response {
            code(io.ktor.http.HttpStatusCode.OK) {
                body<String> {
                    mediaTypes(ContentType.Application.Json)
                    example("ok") {
                        value = """{"status":"ok","tesla_locations_count":1365,"now":1717683240}"""
                    }
                }
            }
        }
    }) {
        val locDir = File(rawDir, "tesla-locations")
        val count =
            if (locDir.isDirectory) {
                locDir.listFiles { f -> f.isDirectory }?.size ?: 0
            } else {
                0
            }
        val now = Instant.now().epochSecond
        call.respondText(
            """{"status":"ok","tesla_locations_count":$count,"now":$now}""",
            ContentType.Application.Json,
        )
    }
}
