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

// Shared response shape for the unified availability endpoints.
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
    val status: AvailabilityStatus,
    val availableCount: Int,
    val total: Int,
    val availableReservableIds: List<String>? = null,
    val reservableStatuses: Map<String, AvailabilityStatus>? = null,
)

fun dayClassificationFromReservableStatuses(
    date: String,
    statuses: Map<String, AvailabilityStatus>,
): DayClassification {
    val sorted = statuses.toSortedMap()
    val availableIds =
        sorted
            .filterValues { it == AvailabilityStatus.AVAILABLE }
            .keys
            .toList()
    return DayClassification(
        date = date,
        status = rollupStatus(sorted.values),
        availableCount = availableIds.size,
        total = sorted.size,
        availableReservableIds = availableIds,
        reservableStatuses = sorted,
    )
}

fun dayClassificationFromStatuses(
    date: String,
    statuses: List<AvailabilityStatus>,
): DayClassification =
    DayClassification(
        date = date,
        status = rollupStatus(statuses),
        availableCount = statuses.count { it == AvailabilityStatus.AVAILABLE },
        total = statuses.size,
    )

fun rollupStatus(statuses: Iterable<AvailabilityStatus>): AvailabilityStatus {
    val values = statuses.toList()
    if (values.isEmpty()) return AvailabilityStatus.UNKNOWN
    return when {
        values.any { it == AvailabilityStatus.AVAILABLE } -> AvailabilityStatus.AVAILABLE
        values.any { it == AvailabilityStatus.FIRST_COME } -> AvailabilityStatus.FIRST_COME
        values.any { it == AvailabilityStatus.UNKNOWN } -> AvailabilityStatus.UNKNOWN
        values.any { it == AvailabilityStatus.RESERVED } -> AvailabilityStatus.RESERVED
        values.all { it == AvailabilityStatus.CLOSED } -> AvailabilityStatus.CLOSED
        else -> AvailabilityStatus.UNKNOWN
    }
}

/** Roll up per-day classifications into a single window-level state. */
fun classifyWindowState(days: List<DayClassification>): String {
    val total = days.sumOf { it.total }
    if (total == 0) return "empty"
    val allClosed = days.all { it.total > 0 && it.status == AvailabilityStatus.CLOSED }
    val anySuccess =
        days.any {
            it.status == AvailabilityStatus.AVAILABLE ||
                it.status == AvailabilityStatus.FIRST_COME ||
                it.status == AvailabilityStatus.UNKNOWN
        }
    val allReserved = days.all { it.total > 0 && it.status == AvailabilityStatus.RESERVED }
    return when {
        allClosed -> "closed_for_season"
        anySuccess -> "success"
        allReserved -> "zero_available"
        else -> "success"
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
    if (state == "zero_available") return "Reserved next $days days"
    val availableDates = perDay.count { it.status == AvailabilityStatus.AVAILABLE }
    val firstComeDates = perDay.count { it.status == AvailabilityStatus.FIRST_COME }
    val unknownDates = perDay.count { it.status == AvailabilityStatus.UNKNOWN }
    if (availableDates == 0 && firstComeDates == 0 && unknownDates > 0) return "Availability unknown"
    val weekendsUnavailable =
        perDay.any { d ->
            val dow = LocalDate.parse(d.date).dayOfWeek
            (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY) &&
                (d.status == AvailabilityStatus.RESERVED || d.status == AvailabilityStatus.CLOSED)
        }
    val tail = if (weekendsUnavailable) " · weekends unavailable" else ""
    val parts = mutableListOf<String>()
    if (availableDates > 0) {
        val noun = if (availableDates == 1) "date" else "dates"
        parts += "$availableDates $noun available"
    }
    if (firstComeDates > 0) {
        val noun = if (firstComeDates == 1) "date" else "dates"
        parts += "$firstComeDates $noun first-come"
    }
    if (parts.isEmpty()) return "Reserved next $days days"
    return parts.joinToString(" · ") + tail
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
    startDate: LocalDate,
    endDate: LocalDate,
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
        window = AvailabilityWindowDto(startDate = startDate.toString(), endDate = endDate.toString()),
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
                    reservableStatuses = day.reservableStatuses,
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
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)

@Serializable
data class AvailabilityDayDto(
    val date: String,
    val status: AvailabilityStatus,
    @SerialName("available_count") val availableCount: Int,
    val total: Int,
    @SerialName("available_reservable_ids") val availableReservableIds: List<String>? = null,
    @SerialName("reservable_statuses") val reservableStatuses: Map<String, AvailabilityStatus>? = null,
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
