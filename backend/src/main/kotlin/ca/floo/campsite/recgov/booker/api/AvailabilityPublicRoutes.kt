package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.campsite.recgov.booker.availability.CachedResult
import ca.floo.campsite.recgov.booker.poller.Campsite
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.service.api.AvailabilityCacheBlock
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.AvailabilitySeasonBlock
import ca.floo.roadtrip.service.api.DayClassification
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.availabilityResponseDto
import ca.floo.roadtrip.service.api.classifyWindowState
import ca.floo.roadtrip.service.api.summarizeWindow
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// Provider-specific helpers for rec.gov campground availability. The HTTP
// surface lives in CampsiteAvailabilityRoutes.kt (single dispatch endpoint
// keyed by poi_id); this file holds the rec.gov classification + fetch
// helpers it calls. Splitting keeps the two providers' upstream details
// isolated while letting them share one wire contract via
// ca.floo.roadtrip.service.api.AvailabilityResponse.

internal val RECGOV_ID_RE = Regex("^[0-9]{1,10}$")

const val DEFAULT_AVAILABILITY_DAYS: Int = 30
const val MAX_AVAILABILITY_DAYS: Int = 60

/** UTC helper, kept here so the dispatch route doesn't need to import ZoneOffset. */
fun todayUtc(): LocalDate = LocalDate.now(ZoneOffset.UTC)

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
 * `minNights` controls same-site multi-night classification: a day D is
 * "available" iff at least one site is Available for all N consecutive
 * nights starting D. Passing 1 collapses to single-night classification
 * (the legacy behavior). The caller is responsible for ensuring `months`
 * covers `[start, start + days + minNights - 2]` so the rolling window
 * doesn't truncate at the visible-window edge.
 */
internal suspend fun fetchAndClassifyRecgov(
    cache: CachedAvailability,
    recgovId: String,
    today: LocalDate,
    days: Int,
    months: List<String>,
    force: Boolean,
    minNights: Int = 1,
): AvailabilityResponseDto =
    coroutineScope {
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force) } }
                .awaitAll()

        // Same campsite id may appear in both months; keep the union.
        val merged: Map<String, Map<String, String>> = mergeCampsites(results.map { it.data })

        val dates = (0 until days).map { today.plusDays(it.toLong()).toString() }
        val perDay = dates.map { date -> classifyDay(merged, date, minNights.coerceAtLeast(1)) }

        val state = classifyWindowState(perDay)
        val summary = summarizeWindow(days, perDay, state)
        val cacheBlock = aggregateCacheBlock(results)
        val seasonBlock = if (state == "closed_for_season") inferReopenDate(merged, today) else null

        availabilityResponseDto(
            provider = "recgov",
            today = today,
            days = days,
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
    today: LocalDate,
    days: Int,
    months: List<String>,
    force: Boolean,
    minNights: Int = 1,
): AvailabilityResponseDto =
    coroutineScope {
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force) } }
                .awaitAll()

        val merged = mergeCampsites(results.map { it.data })
        val oneSite = merged[campsiteId]?.let { mapOf(campsiteId to it) } ?: emptyMap()

        val dates = (0 until days).map { today.plusDays(it.toLong()).toString() }
        val perDay = dates.map { date -> classifyDay(oneSite, date, minNights.coerceAtLeast(1)) }

        val state = classifyWindowState(perDay)
        val summary = summarizeWindow(days, perDay, state)
        val cacheBlock = aggregateCacheBlock(results)
        val seasonBlock = if (state == "closed_for_season") inferReopenDate(oneSite, today) else null

        availabilityResponseDto(
            provider = "recgov",
            today = today,
            days = days,
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
 * Bulk variant: returns just the dates inside [start, start+nights-1] where
 * at least one site is bookable. Reuses the same cache as the single-id path.
 *
 * "Bookable" here means available *for nights consecutive nights starting
 * that date*. This matches the bulk endpoint's contract — the caller is
 * asking "which arrival dates work for an N-night stay?", not "which dates
 * have any open site."
 */
suspend fun availableDatesRecgov(
    cache: CachedAvailability,
    recgovId: String,
    start: LocalDate,
    nights: Int,
): List<String> =
    coroutineScope {
        // Cover the full range each candidate window touches: start through
        // start + (nights-1) for the last arrival + (nights-1) trailing nights.
        val lastArrival = start.plusDays((nights - 1).toLong())
        val end = lastArrival.plusDays((nights - 1).toLong())
        val months = monthsCovering(start, end)
        val results: List<CachedResult> =
            months
                .map { month -> async { cache.get("recgov", recgovId, month, force = false) } }
                .awaitAll()
        val merged = mergeCampsites(results.map { it.data })
        (0 until nights)
            .map { start.plusDays(it.toLong()).toString() }
            .filter { date ->
                val cls = classifyDay(merged, date, nights.coerceAtLeast(1))
                cls.status == "available" || cls.status == "partial"
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

/**
 * Classify a single arrival day's availability for an N-night same-site stay.
 *
 * For each campsite, the site qualifies as "available" only if it's reported
 * Available for every night from `date` through `date + minNights - 1`. A
 * single Closed or booked night anywhere in that window disqualifies the
 * site for that arrival day.
 *
 * Site totals (`total`) count sites that have *any* status for the arrival
 * date — that's how we tell the visible-window classifier between "no data"
 * and "fully booked." A site missing from the upstream feed for the arrival
 * date doesn't contribute to either tally; one with a non-Closed status on
 * the arrival but missing data on a trailing night counts as booked (the
 * upstream simply doesn't sell beyond the season's end on that site).
 */
private fun classifyDay(
    merged: Map<String, Map<String, String>>,
    date: String,
    minNights: Int,
): DayClassification {
    val arrivalDate = LocalDate.parse(date)
    val window = (0 until minNights).map { arrivalDate.plusDays(it.toLong()).toString() }

    var availForStay = 0 // sites Available for ALL N nights
    var booked = 0
    var closed = 0
    for ((_, byDate) in merged) {
        val arrivalStatus = byDate[date] ?: continue
        when {
            arrivalStatus.equals("Closed", true) -> closed++
            isOpen(arrivalStatus) -> {
                if (window.all { d -> isOpen(byDate[d]) }) availForStay++ else booked++
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

private fun isOpen(s: String?): Boolean = s != null && (s.equals("Available", true) || s.equals("Open", true))

private fun inferReopenDate(
    merged: Map<String, Map<String, String>>,
    today: LocalDate,
): AvailabilitySeasonBlock? {
    val candidates =
        merged.values
            .flatMap { it.entries }
            .filter { (_, status) ->
                status.equals("Available", true) || status.equals("Open", true)
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

/**
 * Tiny per-IP token-bucket rate limiter. Not battle-tested; this is a
 * single-user side project. The limit's job is to make casual scraping
 * unprofitable, not survive a determined attacker.
 */
internal class IpRateLimiter(
    private val perMinute: Int,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val refillPerMs = perMinute / 60_000.0

    fun allow(ip: String): Boolean {
        val now = nowMs()
        val bucket =
            buckets.compute(ip) { _, existing ->
                val b = existing ?: Bucket(perMinute.toDouble(), now)
                val delta = now - b.lastRefillMs
                b.tokens = (b.tokens + delta * refillPerMs).coerceAtMost(perMinute.toDouble())
                b.lastRefillMs = now
                b
            }!!
        return synchronized(bucket) {
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }
}
