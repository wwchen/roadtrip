package ca.floo.roadtrip.service.api.recgov

import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.service.api.AvailabilityCacheBlock
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.AvailabilitySeasonBlock
import ca.floo.roadtrip.service.api.AvailabilityStatus
import ca.floo.roadtrip.service.api.DayClassification
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.availabilityResponseDto
import ca.floo.roadtrip.service.api.classifyWindowState
import ca.floo.roadtrip.service.api.dayClassificationFromReservableStatuses
import ca.floo.roadtrip.service.api.summarizeWindow
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// Provider-specific helpers for rec.gov campground availability. The HTTP
// surface lives in AvailabilityRoutes.kt; this file holds rec.gov
// classification + fetch helpers so upstream details stay below BookingProvider.

private fun daysBetween(
    startDate: LocalDate,
    endDate: LocalDate,
): Int =
    java.time.temporal.ChronoUnit.DAYS
        .between(startDate, endDate)
        .toInt()

/** Months (as YYYY-MM-01 strings) covering the inclusive range [start, end]. */
internal fun monthsCovering(
    start: LocalDate,
    end: LocalDate,
): List<String> {
    val out = mutableListOf<String>()
    var ym = YearMonth.from(start)
    val endYm = YearMonth.from(end)
    while (!ym.isAfter(endYm)) {
        out += ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        ym = ym.plusMonths(1)
    }
    return out
}

/**
 * Fetch every relevant month from cache, classify into per-day buckets, and
 * render the unified response. Throws on upstream failure — caller maps
 * to a 503.
 *
 * The half-open window `[startDate, endDate)` is classified as independent
 * calendar days. Same-site stay-length matching belongs to alert execution,
 * not public/provider availability.
 */
internal suspend fun fetchAndClassifyRecgov(
    cache: CachedAvailability,
    recgovId: String,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityResponseDto =
    coroutineScope {
        val days = daysBetween(startDate, endDate)
        val months = monthsCovering(startDate, endDate.minusDays(1))
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force) } }
                .awaitAll()

        // Same campsite id may appear in both months; keep the union.
        val merged: Map<String, Map<String, String>> = mergeCampsites(results.map { it.data })

        val dates = (0 until days).map { startDate.plusDays(it.toLong()).toString() }
        val perDay = dates.map { date -> classifyDay(merged, date) }

        val state = classifyWindowState(perDay)
        val summary = summarizeWindow(days, perDay, state)
        val cacheBlock = aggregateCacheBlock(results)
        val seasonBlock = if (state == "closed_for_season") inferReopenDate(merged, startDate) else null

        availabilityResponseDto(
            provider = "recgov",
            startDate = startDate,
            endDate = endDate,
            perDay = perDay,
            state = state,
            summary = summary,
            seasonBlock = seasonBlock,
            cacheBlock = cacheBlock,
            campgroundId = recgovId,
        )
    }

/**
 * Same cached upstream fetch as [fetchAndClassifyRecgov], narrowed to a linked
 * reservable catalog. This lets `/api/poi/{id}/availability?site_type=...`
 * classify only the matching POI sites without a per-site upstream loop.
 */
internal suspend fun fetchAndClassifyRecgovCatalog(
    cache: CachedAvailability,
    recgovId: String,
    campsiteIds: Set<String>,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityResponseDto =
    coroutineScope {
        val days = daysBetween(startDate, endDate)
        val months = monthsCovering(startDate, endDate.minusDays(1))
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force) } }
                .awaitAll()

        val merged = mergeCampsites(results.map { it.data })
        val catalogSites = merged.filterKeys { it in campsiteIds }

        val dates = (0 until days).map { startDate.plusDays(it.toLong()).toString() }
        val perDay = dates.map { date -> classifyDay(catalogSites, date) }

        val state = classifyWindowState(perDay)
        val summary = summarizeWindow(days, perDay, state)
        val cacheBlock = aggregateCacheBlock(results)
        val seasonBlock = if (state == "closed_for_season") inferReopenDate(catalogSites, startDate) else null

        availabilityResponseDto(
            provider = "recgov",
            startDate = startDate,
            endDate = endDate,
            perDay = perDay,
            state = state,
            summary = summary,
            seasonBlock = seasonBlock,
            cacheBlock = cacheBlock,
            campgroundId = recgovId,
        )
    }

/**
 * Same cached upstream fetch as [fetchAndClassifyRecgov], narrowed to one
 * rec.gov campsite id. This powers `/api/reservable/{rid}/availability`.
 */
internal suspend fun fetchAndClassifyRecgovReservable(
    cache: CachedAvailability,
    recgovId: String,
    campsiteId: String,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityResponseDto =
    coroutineScope {
        val days = daysBetween(startDate, endDate)
        val months = monthsCovering(startDate, endDate.minusDays(1))
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force) } }
                .awaitAll()

        val merged = mergeCampsites(results.map { it.data })
        val oneSite = merged[campsiteId]?.let { mapOf(campsiteId to it) } ?: emptyMap()

        val dates = (0 until days).map { startDate.plusDays(it.toLong()).toString() }
        val perDay = dates.map { date -> classifyDay(oneSite, date) }

        val state = classifyWindowState(perDay)
        val summary = summarizeWindow(days, perDay, state)
        val cacheBlock = aggregateCacheBlock(results)
        val seasonBlock = if (state == "closed_for_season") inferReopenDate(oneSite, startDate) else null

        availabilityResponseDto(
            provider = "recgov",
            startDate = startDate,
            endDate = endDate,
            perDay = perDay,
            state = state,
            summary = summary,
            seasonBlock = seasonBlock,
            cacheBlock = cacheBlock,
            campgroundId = recgovId,
            reservableId = "site:recgov:$campsiteId",
        )
    }

/**
 * Bulk variant: returns just the dates inside `[startDate, endDate)` where
 * at least one site is available that day. Reuses the same cache as the
 * single-id path.
 */
suspend fun availableDatesRecgov(
    cache: CachedAvailability,
    recgovId: String,
    startDate: LocalDate,
    endDate: LocalDate,
): List<String> =
    coroutineScope {
        val days = daysBetween(startDate, endDate)
        val months = monthsCovering(startDate, endDate.minusDays(1))
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force = false) } }
                .awaitAll()
        val merged = mergeCampsites(results.map { it.data })
        (0 until days)
            .map { startDate.plusDays(it.toLong()).toString() }
            .filter { date ->
                val cls = classifyDay(merged, date)
                cls.availableCount > 0
            }
    }

private fun mergeCampsites(maps: List<Map<String, Campsite>>): Map<String, Map<String, String>> {
    val out = mutableMapOf<String, MutableMap<String, String>>()
    for (m in maps) {
        for ((id, cs) in m) {
            val target = out.getOrPut(id) { mutableMapOf() }
            for ((rawDate, status) in cs.availabilities) {
                // rec.gov keys with full ISO timestamp; normalize to YYYY-MM-DD.
                val day = rawDate.substring(0, minOf(10, rawDate.length))
                target[day] = status
            }
        }
    }
    return out
}

private fun classifyDay(
    merged: Map<String, Map<String, String>>,
    date: String,
): DayClassification {
    val statuses =
        merged
            .mapKeys { (siteId, _) -> recgovReservableId(siteId) }
            .mapValues { (_, byDate) -> classifyRecgovStatus(byDate[date]) }
    return dayClassificationFromReservableStatuses(date, statuses)
}

private fun recgovReservableId(siteId: String): String = "site:recgov:$siteId"

private fun classifyRecgovStatus(raw: String?): AvailabilityStatus {
    val status = raw?.trim()
    if (status.isNullOrEmpty()) return AvailabilityStatus.UNKNOWN
    return when {
        status.equals("null", true) -> AvailabilityStatus.UNKNOWN
        status.equals("Available", true) || status.equals("Open", true) -> AvailabilityStatus.AVAILABLE
        status.equals("Not Reservable", true) -> AvailabilityStatus.FIRST_COME
        status.equals("Closed", true) -> AvailabilityStatus.CLOSED
        status.equals("Reserved", true) -> AvailabilityStatus.RESERVED
        else -> AvailabilityStatus.RESERVED
    }
}

private fun inferReopenDate(
    merged: Map<String, Map<String, String>>,
    today: LocalDate,
): AvailabilitySeasonBlock? {
    val candidates =
        merged.values
            .flatMap { it.entries }
            .filter { (_, status) ->
                classifyRecgovStatus(status).isOnlineBookable
            }.mapNotNull { (date, _) ->
                runCatching { LocalDate.parse(date) }.getOrNull()
            }.filter { !it.isBefore(today) }
    val earliest = candidates.minOrNull() ?: return null
    return AvailabilitySeasonBlock(reopensOn = earliest.toString())
}

private fun aggregateCacheBlock(results: List<CachedResult>): AvailabilityCacheBlock =
    AvailabilityCacheBlock(
        hit = results.all { it.hit },
        ageSeconds = results.maxOfOrNull { it.ageSeconds } ?: 0L,
        ttlSeconds = results.firstOrNull()?.ttlSeconds ?: 7200L,
    )

internal fun mapRecgovUpstreamError(e: Throwable): Pair<HttpStatusCode, AvailabilityErrorSchema> {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("429") ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("rate_limited", retryAfterS = 60)
        else ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_5xx", retryAfterS = 30)
    }
}
