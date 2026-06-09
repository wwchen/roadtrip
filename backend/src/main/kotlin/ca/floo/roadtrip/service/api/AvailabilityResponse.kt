package ca.floo.roadtrip.service.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

// Shared response shape for the unified /api/campsite/availability endpoint.
// Both rec.gov and Aspira providers feed the same downstream classification
// + render path so the FE drawer.js doesn't have to learn two contracts.
//
// Each provider's route is responsible for translating its upstream payload
// into a List<DayClassification> via its own classifyDays() — only the
// inputs differ (rec.gov: per-campsite per-day status strings; Aspira:
// per-sub-area per-day status codes). Everything below is provider-agnostic.

data class DayClassification(
    val date: String,
    val status: String, // "available" | "partial" | "booked" | "closed"
    val availableCount: Int,
    val total: Int,
)

/** Roll up per-day classifications into a single window-level state. */
fun classifyWindowState(days: List<DayClassification>): String {
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

/** One-line human summary for the drawer header. */
fun summarizeWindow(
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

/**
 * Build the JSON the FE drawer reads. Stable across providers:
 *   - `provider`: "recgov" | "aspira" so the FE can pick provider-specific
 *     CTAs without having to keep its own classifier.
 *   - `season`: optional reopen-date hint; only rec.gov surfaces this today.
 *   - Provider-specific extras (recgov: campground_id; aspira: host, map_id)
 *     are added via [extras]. Pure additive — the FE ignores unknown fields.
 */
fun renderAvailabilityJson(
    provider: String,
    today: LocalDate,
    days: Int,
    perDay: List<DayClassification>,
    state: String,
    summary: String,
    seasonBlock: JsonObject?,
    cacheBlock: JsonObject,
    extras: Map<String, String> = emptyMap(),
): String =
    buildJsonObject {
        put("provider", provider)
        for ((k, v) in extras) put(k, v)
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
