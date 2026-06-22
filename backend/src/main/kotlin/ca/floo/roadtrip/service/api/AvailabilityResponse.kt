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
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
    val availableReservableIds: List<String>? = null,
    val reservableStatuses: Map<String, AvailabilityStatus>? = null,
)

data class ReservableDayObservation(
    val reservableId: String,
    val date: LocalDate,
    val observedAt: Instant,
    val status: AvailabilityStatus,
)

data class AvailabilityObservationBatch(
    val provider: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observations: List<ReservableDayObservation>,
    val cacheBlock: AvailabilityCacheBlock,
    val seasonBlock: AvailabilitySeasonBlock? = null,
    val campgroundId: String? = null,
    val host: String? = null,
    val mapId: String? = null,
    val reservableId: String? = null,
)

fun dayClassificationsFromObservations(
    startDate: LocalDate,
    endDate: LocalDate,
    observations: List<ReservableDayObservation>,
): List<DayClassification> {
    val byDate =
        observations
            .asSequence()
            .filter { !it.date.isBefore(startDate) && it.date.isBefore(endDate) }
            .groupBy { it.date }
    val days = ChronoUnit.DAYS.between(startDate, endDate).toInt()
    return (0 until days).map { offset ->
        val date = startDate.plusDays(offset.toLong())
        val latestByReservable = linkedMapOf<String, ReservableDayObservation>()
        for (observation in byDate[date].orEmpty()) {
            val current = latestByReservable[observation.reservableId]
            if (current == null || !observation.observedAt.isBefore(current.observedAt)) {
                latestByReservable[observation.reservableId] = observation
            }
        }
        val statuses = latestByReservable.mapValues { (_, observation) -> observation.status }
        dayClassificationFromReservableStatuses(date.toString(), statuses)
    }
}

fun availabilityDatesFromObservations(batch: AvailabilityObservationBatch): List<String> =
    dayClassificationsFromObservations(batch.startDate, batch.endDate, batch.observations)
        .filter { it.availableReservableIds.orEmpty().isNotEmpty() }
        .map { it.date }

fun availabilityResponseFromObservations(batch: AvailabilityObservationBatch): AvailabilityResponseDto {
    val perDay = dayClassificationsFromObservations(batch.startDate, batch.endDate, batch.observations)
    val state = classifyWindowState(perDay)
    return availabilityResponseDto(
        provider = batch.provider,
        startDate = batch.startDate,
        endDate = batch.endDate,
        perDay = perDay,
        state = state,
        seasonBlock = batch.seasonBlock.takeIf { state == "closed_for_season" },
        cacheBlock = batch.cacheBlock,
        campgroundId = batch.campgroundId,
        host = batch.host,
        mapId = batch.mapId,
        reservableId = batch.reservableId,
    )
}

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
        availableReservableIds = availableIds,
        reservableStatuses = sorted,
    )
}

fun dayClassificationFromStatuses(
    date: String,
    statuses: List<AvailabilityStatus>,
): DayClassification {
    val keyedStatuses =
        statuses
            .mapIndexed { index, status -> index.toString() to status }
            .toMap()
    return dayClassificationFromReservableStatuses(
        date = date,
        statuses = keyedStatuses,
    )
}

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
    if (days.none { it.reservableStatuses.orEmpty().isNotEmpty() }) return "empty"
    val allClosed = days.all { it.reservableStatuses.orEmpty().isNotEmpty() && it.status == AvailabilityStatus.CLOSED }
    val anySuccess =
        days.any {
            it.status == AvailabilityStatus.AVAILABLE ||
                it.status == AvailabilityStatus.FIRST_COME ||
                it.status == AvailabilityStatus.UNKNOWN
        }
    val allReserved = days.all { it.reservableStatuses.orEmpty().isNotEmpty() && it.status == AvailabilityStatus.RESERVED }
    return when {
        allClosed -> "closed_for_season"
        anySuccess -> "success"
        allReserved -> "zero_available"
        else -> "success"
    }
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
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        state = state,
        season = seasonBlock?.let { availabilityResponseJson.encodeToJsonElement(it) } ?: JsonNull,
        availability =
            perDay.map { day ->
                AvailabilityDayDto(
                    date = day.date,
                    status = day.status,
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
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val state: String,
    val season: JsonElement,
    val availability: List<AvailabilityDayDto>,
    val cache: AvailabilityCacheBlock,
)

@Serializable
data class AvailabilityDayDto(
    val date: String,
    val status: AvailabilityStatus,
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

// /api/poi/{poi_id}/reservables/availability — bulk per-reservable availability
// for one POI. Each entry in `reservables` is the same envelope the single-
// reservable endpoint returns; the FE fuses them into the week grid.
//
// `reservables` is empty when the POI has no linked reservables (walk-up /
// non-bookable). The drawer should hide the matrix in that case.
@Serializable
data class PoiReservablesAvailabilityResponseDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val reservables: List<AvailabilityResponseDto>,
)

fun availabilityErrorDto(
    error: String,
    upstreamStatus: Int? = null,
    earliestDate: String? = null,
    timeZone: String? = null,
    latestDate: String? = null,
    maxDays: Int? = null,
): AvailabilityErrorSchema =
    AvailabilityErrorSchema(
        error = error,
        upstream_status = upstreamStatus,
        earliestDate = earliestDate,
        timeZone = timeZone,
        latestDate = latestDate,
        maxDays = maxDays,
    )
