package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
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

@OptIn(ExperimentalSerializationApi::class)
@PublishedApi
internal val availabilityResponseJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

inline fun <reified T> encodeAvailabilityJson(value: T): String = availabilityResponseJson.encodeToString(value)

data class DayClassification(
    val date: String,
    val status: String, // "available" | "partial" | "booked" | "closed"
    val availableCount: Int,
    val total: Int,
    val availableReservableIds: List<String>? = null,
)

/** Roll up per-day classifications into a single window-level state. */
fun classifyWindowState(days: List<DayClassification>): String {
    val total = days.sumOf { it.total }
    if (total == 0) return "empty"
    val anyBookable = days.any { it.availableCount > 0 }
    val anyStayMismatch = days.any { it.status == "partial" }
    val allClosed = days.all { it.status == "closed" || it.total == 0 }
    return when {
        allClosed -> "closed_for_season"
        anyBookable || anyStayMismatch -> "success"
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
    val nightsAvailable = perDay.count { it.availableCount > 0 }
    if (nightsAvailable == 0 && perDay.any { it.status == "partial" }) return "Openings need a shorter stay"
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
 *   - Provider-specific fields (recgov: campground_id; aspira: host, map_id)
 *     are additive — the FE ignores unknown fields.
 */
fun availabilityResponseDto(
    provider: String,
    today: LocalDate,
    days: Int,
    perDay: List<DayClassification>,
    state: String,
    summary: String,
    seasonBlock: AvailabilitySeasonBlock?,
    cacheBlock: AvailabilityCacheBlock,
    campgroundId: String? = null,
    host: String? = null,
    mapId: String? = null,
    reservableId: String? = null,
): AvailabilityResponseDto =
    AvailabilityResponseDto(
        provider = provider,
        campgroundId = campgroundId,
        host = host,
        mapId = mapId,
        reservableId = reservableId,
        checkedAt = Instant.now().toString(),
        window = AvailabilityWindowDto(start = today.toString(), days = days),
        summary = summary,
        state = state,
        season = seasonBlock?.let { availabilityResponseJson.encodeToJsonElement(it) } ?: JsonNull,
        availability =
            perDay.map { day ->
                AvailabilityDayDto(
                    date = day.date,
                    status = day.status,
                    availableCount = day.availableCount,
                    total = day.total,
                    availableReservableIds = day.availableReservableIds,
                )
            },
        cache = cacheBlock,
    )

@Serializable
data class AvailabilityResponseDto(
    val provider: String,
    @SerialName("campground_id") val campgroundId: String? = null,
    val host: String? = null,
    @SerialName("map_id") val mapId: String? = null,
    @SerialName("reservable_id") val reservableId: String? = null,
    @SerialName("checked_at") val checkedAt: String,
    val window: AvailabilityWindowDto,
    val summary: String,
    val state: String,
    val season: JsonElement,
    val availability: List<AvailabilityDayDto>,
    val cache: AvailabilityCacheBlock,
)

@Serializable
data class AvailabilityWindowDto(
    val start: String,
    val days: Int,
)

@Serializable
data class AvailabilityDayDto(
    val date: String,
    val status: String,
    @SerialName("available_count") val availableCount: Int,
    val total: Int,
    @SerialName("available_reservable_ids") val availableReservableIds: List<String>? = null,
)

@Serializable
data class AvailabilitySeasonBlock(
    @SerialName("reopens_on") val reopensOn: String,
)

@Serializable
data class AvailabilityCacheBlock(
    val hit: Boolean,
    @SerialName("age_seconds") val ageSeconds: Long,
    @SerialName("ttl_seconds") val ttlSeconds: Long,
)

fun availabilityErrorDto(
    error: String,
    retryAfterS: Int? = null,
): AvailabilityErrorSchema = AvailabilityErrorSchema(error = error, retry_after_s = retryAfterS)
