package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.campsite.recgov.booker.availability.CachedResult
import ca.floo.campsite.recgov.booker.poller.Campsite
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("AvailabilityPublicRoutes")

private val RECGOV_ID_RE = Regex("^[0-9]{1,10}$")
private const val DEFAULT_DAYS = 30
private const val MAX_DAYS = 60

/**
 * GET /api/campsite/availability/{recgov_id}?days=30[&force=1]
 *
 * Public route the landing-page drawer hits to populate availability for a
 * US federal campground. Wraps [CachedAvailability] (which wraps
 * AvailabilityClient.fetchMonth) and classifies the response into one of
 * four states the frontend renders distinctly.
 *
 * Hardening (per RFC 0003 / autoplan eng review):
 *   - Path param validated as `^[0-9]{1,10}$` — never interpolate raw input
 *     into the outbound rec.gov URL.
 *   - `days` clamped to 1..60 (default 30) — prevent fan-out abuse.
 *   - Per-IP token bucket (30 req/min) — single-user side project but the
 *     URL is public; this stops a botnet from turning the backend into a
 *     rec.gov scraping proxy.
 *   - Month-boundary spanning: a 30-day window starting day 25 of June
 *     touches both June and July; both months are fetched in parallel.
 *   - Errors land as 503 with `retry_after_s` so the frontend's error state
 *     can show a useful retry banner.
 */
fun Route.availabilityPublicRoutes(
    cache: CachedAvailability,
    knownIds: () -> Set<String> = { emptySet() }, // optional 404 gate when known
) {
    val rateLimit = IpRateLimiter(perMinute = 30)

    get("/api/campsite/availability/{recgov_id}", {
        tags = listOf("campsite")
        summary = "30-day campsite availability snapshot for one rec.gov campground (cached)"
    }) {
        val recgovId = call.parameters["recgov_id"].orEmpty()
        if (!RECGOV_ID_RE.matches(recgovId)) {
            call.respondText(
                """{"state":"error","error":"bad_recgov_id"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        val days =
            call.request.queryParameters["days"]
                ?.toIntOrNull()
                ?: DEFAULT_DAYS
        if (days !in 1..MAX_DAYS) {
            call.respondText(
                """{"state":"error","error":"bad_days"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        val knownSet = knownIds()
        if (knownSet.isNotEmpty() && recgovId !in knownSet) {
            call.respondText(
                """{"state":"error","error":"unknown_campground"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
            return@get
        }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondText(
                """{"state":"error","error":"ip_throttled","retry_after_s":30}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val force = call.request.queryParameters["force"] == "1"
        val today = LocalDate.now(ZoneOffset.UTC)
        val end = today.plusDays((days - 1).toLong())
        val months = monthsCovering(today, end)

        val result =
            try {
                fetchAndClassify(cache, recgovId, today, days, months, force)
            } catch (e: Exception) {
                val (httpStatus, errorBody) = mapUpstreamError(e)
                log.info("availability {} failed: {}", recgovId, e.message)
                call.respondText(errorBody, io.ktor.http.ContentType.Application.Json, httpStatus)
                return@get
            }

        call.respondText(result, io.ktor.http.ContentType.Application.Json)
    }
}

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

private suspend fun fetchAndClassify(
    cache: CachedAvailability,
    recgovId: String,
    today: LocalDate,
    days: Int,
    months: List<String>,
    force: Boolean,
): String =
    coroutineScope {
        // Parallel month fetches; coalesces inside the cache for same-key concurrent waiters.
        val results: List<CachedResult> =
            months
                .map { month ->
                    async { cache.get("recgov", recgovId, month, force) }
                }.awaitAll()

        // Merge availabilities across months into one map<date-string, status>.
        // The same campsite id may appear in both months; keep the union.
        val merged: Map<String, Map<String, String>> = mergeCampsites(results.map { it.data })

        // Build the per-day window list (today..today+days-1).
        val dates = (0 until days).map { today.plusDays(it.toLong()).toString() }
        val perDay =
            dates.map { date ->
                classifyDay(merged, date)
            }

        val state = classifyState(perDay)
        val summary = summarize(today, days, perDay, state)
        val cacheBlock = aggregateCacheBlock(results)
        val seasonBlock = if (state == "closed_for_season") inferReopenDate(merged, today) else null

        renderJson(
            recgovId = recgovId,
            today = today,
            days = days,
            perDay = perDay,
            state = state,
            summary = summary,
            seasonBlock = seasonBlock,
            cacheBlock = cacheBlock,
        )
    }

private fun mergeCampsites(maps: List<Map<String, Campsite>>): Map<String, Map<String, String>> {
    // Returns campsiteId -> (date-only -> status). rec.gov's status field is
    // a string like "Available", "Reserved", "Open", "Not Available", "Closed".
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

private data class DayClassification(
    val date: String,
    val status: String, // "available" | "partial" | "booked" | "closed"
    val availableCount: Int,
    val total: Int,
)

private fun classifyDay(
    merged: Map<String, Map<String, String>>,
    date: String,
): DayClassification {
    var avail = 0
    var booked = 0
    var closed = 0
    for ((_, byDate) in merged) {
        val s = byDate[date] ?: continue
        when {
            s.equals("Available", true) || s.equals("Open", true) -> avail++
            s.equals("Closed", true) -> closed++
            else -> booked++
        }
    }
    val total = avail + booked + closed
    val status =
        when {
            total == 0 -> "closed" // no data treated as closed for the day; whole-window classification picks up "empty"
            closed == total -> "closed"
            avail == 0 -> "booked"
            avail == total -> "available"
            else -> "partial"
        }
    return DayClassification(date, status, avail, total)
}

private fun classifyState(days: List<DayClassification>): String {
    val total = days.sumOf { it.total }
    if (total == 0) return "empty"
    val anyAvail = days.any { it.status == "available" || it.status == "partial" }
    val allClosed = days.all { it.status == "closed" || it.total == 0 }
    return when {
        allClosed -> "closed_for_season"
        anyAvail -> "success"
        else -> "zero_available"
    }
}

private fun summarize(
    @Suppress("UNUSED_PARAMETER") today: LocalDate,
    days: Int,
    perDay: List<DayClassification>,
    state: String,
): String {
    if (state == "empty") return "No availability data"
    if (state == "closed_for_season") return "Closed for season"
    val nightsAvailable = perDay.count { it.status == "available" || it.status == "partial" }
    if (state == "zero_available") return "Fully booked next $days days"
    val weekendsBooked =
        perDay.any { d ->
            val dow = LocalDate.parse(d.date).dayOfWeek
            (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY) &&
                (d.status == "booked" || d.status == "closed")
        }
    val tail = if (weekendsBooked) " · weekends full" else ""
    val noun = if (nightsAvailable == 1) "night" else "nights"
    return "$nightsAvailable $noun available$tail"
}

private fun inferReopenDate(
    merged: Map<String, Map<String, String>>,
    today: LocalDate,
): JsonObject? {
    // Find the earliest date >= today where any campsite is "Available" or "Open".
    val candidates =
        merged.values
            .flatMap { it.entries }
            .filter { (_, status) ->
                status.equals("Available", true) || status.equals("Open", true)
            }.mapNotNull { (date, _) ->
                runCatching { LocalDate.parse(date) }.getOrNull()
            }.filter { !it.isBefore(today) }
    val earliest = candidates.minOrNull() ?: return null
    return buildJsonObject { put("reopens_on", earliest.toString()) }
}

private fun aggregateCacheBlock(results: List<CachedResult>): JsonObject =
    buildJsonObject {
        // If any result was a miss, treat as miss; otherwise hit. Age = max age across months.
        put("hit", results.all { it.hit })
        put("age_seconds", results.maxOfOrNull { it.ageSeconds } ?: 0L)
        put("ttl_seconds", results.firstOrNull()?.ttlSeconds ?: 600L)
    }

private fun renderJson(
    recgovId: String,
    today: LocalDate,
    days: Int,
    perDay: List<DayClassification>,
    state: String,
    summary: String,
    seasonBlock: JsonObject?,
    cacheBlock: JsonObject,
): String =
    buildJsonObject {
        put("campground_id", recgovId)
        put(
            "checked_at",
            java.time.Instant
                .now()
                .toString(),
        )
        put(
            "window",
            buildJsonObject {
                put("start", today.toString())
                put("days", days)
            },
        )
        put("summary", summary)
        put("state", state)
        if (seasonBlock != null) put("season", seasonBlock) else put("season", JsonNull)
        put(
            "availability",
            buildJsonArray {
                for (d in perDay) {
                    add(
                        buildJsonObject {
                            put("date", d.date)
                            put("status", d.status)
                            put("available_count", d.availableCount)
                            put("total", d.total)
                        },
                    )
                }
            },
        )
        put("cache", cacheBlock)
    }.toString()

private fun mapUpstreamError(e: Throwable): Pair<HttpStatusCode, String> {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("429") ->
            HttpStatusCode.ServiceUnavailable to
                """{"state":"error","error":"rate_limited","retry_after_s":60}"""
        else ->
            HttpStatusCode.ServiceUnavailable to
                """{"state":"error","error":"upstream_5xx","retry_after_s":30}"""
    }
}

/**
 * Tiny per-IP token-bucket rate limiter. Not battle-tested; this is a single-user
 * side project and the limit's job is to make casual scraping unprofitable, not
 * survive a determined attacker. For that, use a real Ktor rate-limit plugin.
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
                val b =
                    existing
                        ?: Bucket(perMinute.toDouble(), now)
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
