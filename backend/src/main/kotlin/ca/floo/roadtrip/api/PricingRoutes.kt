package ca.floo.roadtrip.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.time.Instant

// Cache-only pricing endpoint. Reads data/pricing-cache/<slug>.json populated
// by scripts/fetch_tesla_superchargers.py (run manually). Misses return 404 so
// the popup can render "Pricing not yet cached" — we never call Tesla from the
// user request path, which means no Akamai 429s, no cookie burns, no curl-
// impersonate dependency in the live serving stack.
fun Route.pricingRoutes(cacheDir: File) {
    get("/api/pricing/{slug}") {
        val slug = call.parameters["slug"]
        if (slug.isNullOrEmpty() || slug.contains('/') || slug.contains("..")) {
            call.respondText(
                """{"error":"bad slug"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }
        val file = File(cacheDir, "$slug.json")
        if (!file.isFile) {
            call.respondText(
                """{"error":"not_cached","message":"Pricing not yet cached for this site."}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return@get
        }
        val ageSeconds = (Instant.now().toEpochMilli() - file.lastModified()) / 1000
        // Splice the _cache hint into the cached JSON without parsing — the
        // file is always a JSON object, so we replace the trailing `}` with
        // `,"_cache":{...}}`. Frontend reads resp._cache for the "cached N
        // ago" footer.
        val body = file.readText().trimEnd()
        val withCache = if (body.endsWith("}")) {
            body.dropLast(1) + ""","_cache":{"age_seconds":$ageSeconds,"hit":true}}"""
        } else {
            body
        }
        call.respondText(withCache, ContentType.Application.Json, HttpStatusCode.OK)
    }
}
