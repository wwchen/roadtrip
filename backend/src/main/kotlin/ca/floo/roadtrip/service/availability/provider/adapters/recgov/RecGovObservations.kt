package ca.floo.roadtrip.service.availability.provider.adapters.recgov

import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.models.api.AvailabilityErrorDto
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.CampsiteDayObservation
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.availability.provider.CatalogCampsiteRef
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// Provider-specific helpers for rec.gov campground availability. The HTTP
// surface lives in AvailabilityRoutes.kt; this file holds rec.gov
// classification + fetch helpers so upstream details stay below AvailabilityProvider.

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
 * Fetch every relevant month and translate upstream statuses into atomic
 * campsite-day observations. Throws on upstream failure — caller maps to a
 * 503.
 *
 * The half-open window `[startDate, endDate)` is classified as independent
 * calendar days. Same-site stay-length matching belongs to alert execution,
 * not public/provider availability.
 */
internal suspend fun fetchRecgovAvailabilityObservations(
    client: RecGovAvailabilityClient,
    recgovId: String,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch =
    coroutineScope {
        val dates = datesInWindow(startDate, endDate)
        val months = monthsCovering(startDate, endDate.minusDays(1))
        val observedAt = Instant.now()
        val payloads: List<Map<String, Campsite>> =
            months
                .map { month -> async { client.fetchMonth(recgovId, month) } }
                .awaitAll()

        // Same campsite id may appear in both months; keep the union.
        val merged: Map<String, Map<String, String>> = mergeCampsites(payloads)
        val observedAtByDate = dates.associateWith { observedAt }

        AvailabilityObservationBatch(
            provider = "recgov",
            startDate = startDate,
            endDate = endDate,
            observations = observationsFromCampsites(merged, dates, observedAtByDate),
            seasonBlock = inferReopenDate(merged, startDate),
            cacheBlock = directFetchCacheBlock(),
            campgroundId = recgovId,
        )
    }

/**
 * Same upstream fetch as [fetchRecgovAvailabilityObservations], narrowed to a
 * linked campsite catalog. This lets `/api/poi/{id}/availability?site_type=...`
 * classify only the matching POI sites without a per-site upstream loop.
 */
internal suspend fun fetchRecgovCatalogObservations(
    client: RecGovAvailabilityClient,
    recgovId: String,
    campsites: List<CatalogCampsiteRef>,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch =
    coroutineScope {
        val dates = datesInWindow(startDate, endDate)
        val months = monthsCovering(startDate, endDate.minusDays(1))
        val observedAt = Instant.now()
        val payloads: List<Map<String, Campsite>> =
            months
                .map { month -> async { client.fetchMonth(recgovId, month) } }
                .awaitAll()

        val merged = mergeCampsites(payloads)
        val campsiteIds = campsites.map { it.vendorId }.toSet()
        val catalogSites = campsiteIds.associateWith { siteId -> merged[siteId].orEmpty() }
        val campsiteIdsBySiteId = campsites.associate { it.vendorId to it.campsiteId }
        val observedAtByDate = dates.associateWith { observedAt }

        AvailabilityObservationBatch(
            provider = "recgov",
            startDate = startDate,
            endDate = endDate,
            observations = observationsFromCampsites(catalogSites, dates, observedAtByDate, campsiteIdsBySiteId),
            seasonBlock = inferReopenDate(catalogSites, startDate),
            cacheBlock = directFetchCacheBlock(),
            campgroundId = recgovId,
        )
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

private fun datesInWindow(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until daysBetween(startDate, endDate))
        .map { startDate.plusDays(it.toLong()) }

private fun observationsFromCampsites(
    merged: Map<String, Map<String, String>>,
    dates: List<LocalDate>,
    observedAtByDate: Map<LocalDate, Instant>,
    campsiteIdsBySiteId: Map<String, Long> = emptyMap(),
): List<CampsiteDayObservation> =
    merged.flatMap { (siteId, byDate) ->
        dates.map { date ->
            CampsiteDayObservation(
                campsiteId = campsiteIdsBySiteId[siteId],
                date = date,
                observedAt = observedAtByDate[date] ?: Instant.EPOCH,
                status = classifyRecgovStatus(byDate[date.toString()]),
            )
        }
    }

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

private fun directFetchCacheBlock(): AvailabilityCacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L)

internal fun mapRecgovUpstreamError(e: Throwable): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("429") ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("rate_limited")
        else ->
            HttpStatusCode.ServiceUnavailable to availabilityErrorDto("upstream_5xx")
    }
}
