package ca.floo.roadtrip.routes

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import java.time.Instant

// Infra liveness/readiness JSON. Keep this endpoint boring: probes should only
// need to know that the Ktor app booted and can answer requests.
fun Route.healthRoutes() {
    get("/api/health", {
        tags = listOf("health")
        summary = "Application liveness/readiness probe"
        response {
            code(io.ktor.http.HttpStatusCode.OK) {
                body<String> {
                    mediaTypes(ContentType.Application.Json)
                    example("ok") {
                        value = """{"status":"ok","now":1717683240}"""
                    }
                }
            }
        }
    }) {
        val now = Instant.now().epochSecond
        call.respondText(
            """{"status":"ok","now":$now}""",
            ContentType.Application.Json,
        )
    }
}
