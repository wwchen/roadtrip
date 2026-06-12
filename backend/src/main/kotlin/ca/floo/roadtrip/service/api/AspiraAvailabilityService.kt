package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.models.aspira.AspiraStatus
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import io.ktor.http.HttpStatusCode
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
 *
 * `minNights` enforces same-sub-area multi-night classification: a day D is
 * "available" iff at least one sub-area is reported Available for all N
 * consecutive nights starting D. Aspira's per-day status array is fetched
 * for the rolling window so the last visible day's lookup doesn't truncate.
 */
internal suspend fun fetchAndClassifyAspira(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    today: LocalDate,
    days: Int,
    force: Boolean,
    minNights: Int = 1,
): AvailabilityResponseDto {
    val nights = minNights.coerceAtLeast(1)
    // Pull enough trailing data for the rolling window, but only classify
    // the visible `days`. Aspira returns an indexed array per sub-area so
    // extra data is cheap.
    val rollingEnd = today.plusDays((days + nights - 2).toLong())
    val cached = cache.get(host, mapId, today, rollingEnd, force)
    val perDay = classifyDays(cached.data, today, days, nights)
    val state = classifyWindowState(perDay)
    val summary = summarizeWindow(days, perDay, state)
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cached.hit,
            ageSeconds = cached.ageSeconds,
            ttlSeconds = cached.ttlSeconds,
        )
    return availabilityResponseDto(
        provider = "aspira",
        today = today,
        days = days,
        perDay = perDay,
        state = state,
        summary = summary,
        seasonBlock = null, // Aspira doesn't expose reopen-date hints
        cacheBlock = cacheBlock,
        host = host,
        mapId = mapId.toString(),
    )
}

/**
 * Same cached Aspira `/api/availability/map` response as the campground
 * rollup, narrowed to one `resourceAvailabilities` key.
 */
internal suspend fun fetchAndClassifyAspiraResource(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    resourceId: String,
    reservableVendor: String,
    today: LocalDate,
    days: Int,
    force: Boolean,
    minNights: Int = 1,
): AvailabilityResponseDto {
    val nights = minNights.coerceAtLeast(1)
    val rollingEnd = today.plusDays((days + nights - 2).toLong())
    val cached = cache.get(host, mapId, today, rollingEnd, force)
    val resourceDays = cached.data.byResource[resourceId].orEmpty()
    val perDay = classifyResourceDays(resourceDays, today, days, nights)
    val state = classifyWindowState(perDay)
    val summary = summarizeWindow(days, perDay, state)
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cached.hit,
            ageSeconds = cached.ageSeconds,
            ttlSeconds = cached.ttlSeconds,
        )
    return availabilityResponseDto(
        provider = "aspira",
        today = today,
        days = days,
        perDay = perDay,
        state = state,
        summary = summary,
        seasonBlock = null,
        cacheBlock = cacheBlock,
        host = host,
        mapId = mapId.toString(),
        reservableId = "site:$reservableVendor:$resourceId",
    )
}

/**
 * Bulk variant: arrival dates in [start, start+nights-1] where at least one
 * sub-area is bookable for an N-consecutive-night same-sub-area stay.
 */
suspend fun availableDatesAspira(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    start: LocalDate,
    nights: Int,
): List<String> {
    val n = nights.coerceAtLeast(1)
    // Cover the last arrival's trailing nights too.
    val end = start.plusDays((n - 1).toLong()).plusDays((n - 1).toLong())
    val cached = cache.get(host, mapId, start, end, force = false)
    val perDay = classifyDays(cached.data, start, n, n)
    return perDay
        .filter { it.status == "available" || it.status == "partial" }
        .map { it.date }
}

/**
 * Convert Aspira's per-day status arrays into the FE's day-status shape.
 *
 * For each visible arrival day D, a sub-area counts as "available for the
 * stay" iff it reports AVAILABLE for every night from D through D+N-1.
 * `availableCount` is the number of sub-areas that satisfy that window;
 * `total` is the number of sub-areas with any status on the arrival day.
 *
 * When the park has no sub-areas (rare, but possible for a single-loop
 * park), fall back to the `mapAvailabilities` rollup with the same window
 * logic — the rollup just has one virtual sub-area.
 */
private fun classifyDays(
    avail: AspiraAvailability,
    start: LocalDate,
    days: Int,
    minNights: Int,
): List<DayClassification> {
    val nights = minNights.coerceAtLeast(1)
    val sub = avail.byMapLink.values.toList()
    val rollup = avail.parkRollup
    return (0 until days).map { d ->
        val date = start.plusDays(d.toLong()).toString()
        if (sub.isNotEmpty()) {
            classifyArrivalDay(sub, d, nights, date)
        } else {
            // Single virtual sub-area: the rollup. Same window check.
            classifyArrivalDay(listOf(rollup), d, nights, date)
        }
    }
}

private fun classifyResourceDays(
    resourceDays: List<Int>,
    start: LocalDate,
    days: Int,
    minNights: Int,
): List<DayClassification> {
    val nights = minNights.coerceAtLeast(1)
    return (0 until days).map { d ->
        val date = start.plusDays(d.toLong()).toString()
        if (d >= resourceDays.size) {
            DayClassification(date = date, status = "closed", availableCount = 0, total = 0)
        } else {
            classifyResourceArrivalDay(resourceDays, d, nights, date)
        }
    }
}

private fun classifyResourceArrivalDay(
    resourceDays: List<Int>,
    d: Int,
    nights: Int,
    date: String,
): DayClassification {
    val arrivalCls = AspiraStatus.classify(resourceDays[d])
    return when (arrivalCls) {
        "closed" -> DayClassification(date = date, status = "closed", availableCount = 0, total = 1)
        "available", "partial" -> {
            if (!windowAllOpen(resourceDays, d, nights)) {
                DayClassification(date = date, status = "booked", availableCount = 0, total = 1)
            } else {
                DayClassification(date = date, status = arrivalCls, availableCount = 1, total = 1)
            }
        }
        else -> DayClassification(date = date, status = "booked", availableCount = 0, total = 1)
    }
}

/**
 * Run the same-sub-area N-night window check across every sub-area for the
 * arrival-day index `d`. Sub-areas missing a status on the arrival day are
 * not counted in `total` (no data, not "available 0 of 0").
 */
private fun classifyArrivalDay(
    subAreas: List<List<Int>>,
    d: Int,
    nights: Int,
    date: String,
): DayClassification {
    var availForStay = 0
    var booked = 0
    var closed = 0
    for (subDays in subAreas) {
        if (d >= subDays.size) continue
        val arrivalCls = AspiraStatus.classify(subDays[d])
        when (arrivalCls) {
            "closed" -> closed++
            "available", "partial" -> {
                if (windowAllOpen(subDays, d, nights)) availForStay++ else booked++
            }
            else -> booked++
        }
    }
    val total = availForStay + booked + closed
    val status =
        when {
            total == 0 -> "closed"
            closed == total -> "closed"
            availForStay == 0 -> "booked"
            availForStay == total -> "available"
            else -> "partial"
        }
    return DayClassification(date, status, availForStay, total)
}

private fun windowAllOpen(
    subDays: List<Int>,
    d: Int,
    nights: Int,
): Boolean {
    for (offset in 0 until nights) {
        val idx = d + offset
        if (idx >= subDays.size) return false
        val cls = AspiraStatus.classify(subDays[idx])
        if (cls != "available" && cls != "partial") return false
    }
    return true
}

internal fun mapAspiraUpstreamError(e: AspiraException): Pair<HttpStatusCode, AvailabilityErrorSchema> {
    val status = e.httpStatus
    return when {
        status == 429 ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("rate_limited", retryAfterS = 60)
        status == 503 || (e.message?.contains("WAF") == true) ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_blocked", retryAfterS = 300)
        else ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_5xx", retryAfterS = 30)
    }
}
