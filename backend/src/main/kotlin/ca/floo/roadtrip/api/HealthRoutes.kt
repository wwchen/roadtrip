package ca.floo.roadtrip.api

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.time.Instant

// One-shot status JSON. Useful from the phone on the trip to confirm the
// pricing cache is warm and the backend can see Postgres. We deliberately do
// NOT call Tesla here — the cache is populated by a separate refresh worker.
fun Route.healthRoutes(cacheDir: File) {
    get("/api/health") {
        val cacheCount = if (cacheDir.isDirectory) {
            cacheDir.listFiles { _, n -> n.endsWith(".json") }?.size ?: 0
        } else 0
        val now = Instant.now().epochSecond
        call.respondText(
            """{"status":"ok","pricing_cache_count":$cacheCount,"now":$now}""",
            ContentType.Application.Json,
        )
    }
}
