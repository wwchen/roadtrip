package ca.floo.roadtrip.aspira

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("AspiraAvailabilityRoutes")

private const val DEFAULT_DAYS = 30
private const val MAX_DAYS = 60

// Whitelist hosts to prevent SSRF. Anyone hitting this route can't redirect
// the backend at arbitrary URLs by guessing host names.
private val ALLOWED_HOSTS =
    setOf(
        "reservation.pc.gc.ca",
        "washington.goingtocamp.com",
        "discovercamping.ca",
    )

/**
 * GET /api/campsite/availability-aspira/{transactionLocationId}/{mapId}
 *
 * Same FE contract as `/api/campsite/availability/{recgov_id}` — the drawer
 * renderer is provider-agnostic. `host` is a query param (defaults to
 * reservation.pc.gc.ca) so WA state-park campgrounds can hit the same route.
 *
 * `transactionLocationId` is currently unused server-side (the Aspira
 * availability endpoint keys off mapId alone), but it's in the URL so the
 * route mirrors the deeplink shape and so the FE can pass straight from
 * the campground's `aspira` block to the route.
 */
fun Route.aspiraAvailabilityRoutes(cache: CachedAspiraAvailability) {
    val rateLimit = IpRateLimiter(perMinute = 30)

    get("/api/campsite/availability-aspira/{transactionLocationId}/{mapId}", {
        tags = listOf("campsite")
        summary = "30-day availability for one Aspira NextGen campground (Parks Canada / WA State / BC Discover Camping)"
    }) {
        val txParam = call.parameters["transactionLocationId"].orEmpty()
        val mapIdParam = call.parameters["mapId"].orEmpty()
        if (!ID_RE.matches(txParam) || !ID_RE.matches(mapIdParam)) {
            call.respondText(
                """{"state":"error","error":"bad_id"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }
        val mapId = mapIdParam.toInt()

        val host =
            call.request.queryParameters["host"]
                ?: "reservation.pc.gc.ca"
        if (host !in ALLOWED_HOSTS) {
            call.respondText(
                """{"state":"error","error":"bad_host","allowed":${ALLOWED_HOSTS.map { "\"$it\"" }}}""",
                ContentType.Application.Json,
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
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondText(
                """{"state":"error","error":"ip_throttled","retry_after_s":30}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val force = call.request.queryParameters["force"] == "1"
        val today = LocalDate.now(ZoneOffset.UTC)
        val end = today.plusDays((days - 1).toLong())

        try {
            val cached = cache.get(host, mapId, today, end, force)
            val perDay = classifyDays(cached.data, today, days)
            val state = classifyState(perDay)
            val summary = summarize(days, perDay, state)
            val cacheBlock =
                buildJsonObject {
                    put("hit", cached.hit)
                    put("age_seconds", cached.ageSeconds)
                    put("ttl_seconds", cached.ttlSeconds)
                }
            call.respondText(
                renderJson(
                    mapId = mapId,
                    host = host,
                    today = today,
                    days = days,
                    perDay = perDay,
                    state = state,
                    summary = summary,
                    cacheBlock = cacheBlock,
                ),
                ContentType.Application.Json,
            )
        } catch (e: AspiraException) {
            val (status, body) = mapUpstreamError(e)
            log.info("aspira availability host={} mapId={} failed: {}", host, mapId, e.message)
            call.respondText(body, ContentType.Application.Json, status)
        } catch (e: Exception) {
            log.warn("aspira availability host={} mapId={} unexpected: {}", host, mapId, e.message, e)
            call.respondText(
                """{"state":"error","error":"upstream_5xx","retry_after_s":30}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}

private val ID_RE = Regex("^-?\\d+$")

private data class DayClassification(
    val date: String,
    val status: String, // "available" | "partial" | "booked" | "closed"
    val availableCount: Int,
    val total: Int,
)

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
            // Count from sub-areas. Most representative for parks with
            // multiple campgrounds (Banff: 7 sub-areas).
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
                    anyAvail && avCount == 0 -> "partial" // mixed but no full-avail sub-area
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

private fun renderJson(
    mapId: Int,
    host: String,
    today: LocalDate,
    days: Int,
    perDay: List<DayClassification>,
    state: String,
    summary: String,
    cacheBlock: JsonObject,
): String =
    buildJsonObject {
        put("provider", "aspira")
        put("host", host)
        put("map_id", mapId)
        put("checked_at", Instant.now().toString())
        put(
            "window",
            buildJsonObject {
                put("start", today.toString())
                put("days", days)
            },
        )
        put("summary", summary)
        put("state", state)
        put("season", JsonNull) // Aspira doesn't expose reopen-date hints; FE handles null
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

private fun mapUpstreamError(e: AspiraException): Pair<HttpStatusCode, String> {
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

/**
 * Tiny per-IP token-bucket rate limiter. Same shape as the recgov route's,
 * duplicated here to avoid coupling the roadtrip package to the campsite tree.
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
