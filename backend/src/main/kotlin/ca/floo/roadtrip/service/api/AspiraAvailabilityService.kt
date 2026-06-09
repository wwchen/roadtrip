package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.models.aspira.AspiraStatus
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

// Provider-specific helpers for Aspira NextGen availability (Parks Canada,
// BC Provincial, WA State). The HTTP surface lives in
// CampsiteAvailabilityRoutes.kt; this file just translates the cached
// AspiraAvailability payload into the shared response shape.

// Whitelist hosts to prevent SSRF. Anyone hitting the dispatch route can't
// redirect the backend at arbitrary URLs by guessing host names — the host
// is sourced from the registry/pois.source mapping, never a request param.
val ASPIRA_ALLOWED_HOSTS: Set<String> =
    setOf(
        "reservation.pc.gc.ca", // Parks Canada
        "camping.bcparks.ca", // BC Provincial
        "washington.goingtocamp.com", // WA State Parks
    )

/**
 * Fetch + classify + render the unified response for an Aspira-backed
 * campground. Throws on upstream failure — caller maps to a 503.
 */
suspend fun fetchAndClassifyAspira(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    today: LocalDate,
    days: Int,
    force: Boolean,
): String {
    val end = today.plusDays((days - 1).toLong())
    val cached = cache.get(host, mapId, today, end, force)
    val perDay = classifyDays(cached.data, today, days)
    val state = classifyWindowState(perDay)
    val summary = summarizeWindow(days, perDay, state)
    val cacheBlock =
        buildJsonObject {
            put("hit", cached.hit)
            put("age_seconds", cached.ageSeconds)
            put("ttl_seconds", cached.ttlSeconds)
        }
    return renderAvailabilityJson(
        provider = "aspira",
        today = today,
        days = days,
        perDay = perDay,
        state = state,
        summary = summary,
        seasonBlock = null, // Aspira doesn't expose reopen-date hints
        cacheBlock = cacheBlock,
        extras = mapOf("host" to host, "map_id" to mapId.toString()),
    )
}

/**
 * Bulk variant: dates in [start, start+nights-1] where at least one
 * sub-area is bookable.
 */
suspend fun availableDatesAspira(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    start: LocalDate,
    nights: Int,
): List<String> {
    val end = start.plusDays((nights - 1).toLong())
    val cached = cache.get(host, mapId, start, end, force = false)
    val perDay = classifyDays(cached.data, start, nights)
    return perDay
        .filter { it.status == "available" || it.status == "partial" }
        .map { it.date }
}

/**
 * Convert Aspira's per-day status arrays into the FE's day-status shape.
 *
 * Strategy: for each day, count how many sub-areas (mapLink) report each
 * Aspira status, then derive `availableCount` (sub-areas reporting
 * AVAILABLE/LIMITED) and `total` (all sub-areas with a status). When the
 * park has no sub-areas (rare, but possible for a single-loop park),
 * fall back to the `mapAvailabilities` rollup.
 */
private fun classifyDays(
    avail: AspiraAvailability,
    start: LocalDate,
    days: Int,
): List<DayClassification> {
    val sub = avail.byMapLink.values.toList()
    val rollup = avail.parkRollup
    return (0 until days).map { d ->
        val date = start.plusDays(d.toLong()).toString()
        if (sub.isNotEmpty()) {
            var avCount = 0
            var total = 0
            var anyClosed = false
            var anyAvail = false
            for (subDays in sub) {
                if (d >= subDays.size) continue
                val code = subDays[d]
                val cls = AspiraStatus.classify(code)
                total++
                if (cls == "available") {
                    avCount++
                    anyAvail = true
                } else if (cls == "partial") {
                    anyAvail = true
                } else if (cls == "closed") {
                    anyClosed = true
                }
            }
            val status =
                when {
                    total == 0 -> "closed"
                    avCount == total -> "available"
                    anyAvail && avCount == 0 -> "partial"
                    anyAvail -> "partial"
                    anyClosed -> "closed"
                    else -> "booked"
                }
            DayClassification(date, status, avCount, total)
        } else {
            // No sub-areas — single park rollup. `total` is 1 (the park).
            val code = rollup.getOrNull(d) ?: AspiraStatus.NO_DATA
            val cls = AspiraStatus.classify(code)
            DayClassification(date, cls, if (cls == "available") 1 else 0, 1)
        }
    }
}

fun mapAspiraUpstreamError(e: AspiraException): Pair<HttpStatusCode, String> {
    val status = e.httpStatus
    return when {
        status == 429 ->
            HttpStatusCode.ServiceUnavailable to
                """{"state":"error","error":"rate_limited","retry_after_s":60}"""
        status == 503 || (e.message?.contains("WAF") == true) ->
            HttpStatusCode.ServiceUnavailable to
                """{"state":"error","error":"upstream_blocked","retry_after_s":300}"""
        else ->
            HttpStatusCode.ServiceUnavailable to
                """{"state":"error","error":"upstream_5xx","retry_after_s":30}"""
    }
}
