package ca.floo.roadtrip.api

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import java.io.File
import java.time.Instant

// One-shot status JSON. Useful from the phone on the trip to confirm the
// pricing cache is warm and the backend can see Postgres. We deliberately do
// NOT call Tesla here — the cache is populated by a separate refresh worker.
fun Route.healthRoutes(cacheDir: File) {
    get("/api/health", {
        tags = listOf("health")
        summary = "Liveness probe + pricing-cache size; never calls Tesla"
        response {
            code(io.ktor.http.HttpStatusCode.OK) {
                body<String> {
                    mediaTypes(ContentType.Application.Json)
                    example("ok") {
                        value = """{"status":"ok","pricing_cache_count":1365,"now":1717683240}"""
                    }
                }
            }
        }
    }) {
        val cacheCount =
            if (cacheDir.isDirectory) {
                cacheDir.listFiles { _, n -> n.endsWith(".json") }?.size ?: 0
            } else {
                0
            }
        val now = Instant.now().epochSecond
        call.respondText(
            """{"status":"ok","pricing_cache_count":$cacheCount,"now":$now}""",
            ContentType.Application.Json,
        )
    }
}
