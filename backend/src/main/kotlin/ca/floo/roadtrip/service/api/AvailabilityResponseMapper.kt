package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.api.AvailabilityCellDto
import ca.floo.roadtrip.model.api.AvailabilityDayDto
import ca.floo.roadtrip.model.api.AvailabilityErrorDto
import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.DayClassification
import kotlinx.serialization.ExperimentalSerializationApi
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

// Only for embedding the season block as a JsonElement inside the DTO below;
// HTTP serialization belongs to the route layer.
@OptIn(ExperimentalSerializationApi::class)
private val seasonBlockJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

fun dayClassificationsFromObservations(
    startDate: LocalDate,
    endDate: LocalDate,
    observations: List<CampsiteDayObservation>,
): List<DayClassification> {
    val byDate =
        observations
            .asSequence()
            .filter { !it.date.isBefore(startDate) && it.date.isBefore(endDate) }
            .groupBy { it.date }
    val days = ChronoUnit.DAYS.between(startDate, endDate).toInt()
    return (0 until days).map { offset ->
        val date = startDate.plusDays(offset.toLong())
        val latestByCampsite = linkedMapOf<Long, CampsiteDayObservation>()
        for (observation in byDate[date].orEmpty()) {
            val campsiteId = observation.campsiteId ?: continue
            val current = latestByCampsite[campsiteId]
            if (current == null || !observation.observedAt.isBefore(current.observedAt)) {
                latestByCampsite[campsiteId] = observation
            }
        }
        val statuses = latestByCampsite.mapValues { (_, observation) -> observation.status }
        dayClassificationFromCampsiteStatuses(date.toString(), statuses)
    }
}

fun availabilityDatesFromObservations(batch: AvailabilityObservationBatch): List<String> =
    dayClassificationsFromObservations(batch.startDate, batch.endDate, batch.observations)
        .filter { it.availableCampsiteIds.orEmpty().isNotEmpty() }
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
        seasonBlock = batch.seasonBlock.takeIf { state == AvailabilityWindowState.CLOSED_FOR_SEASON.wireValue },
        cacheBlock = batch.cacheBlock,
        scopeRef = batch.scope?.serialize(),
        campsiteId = batch.campsiteId,
    )
}

fun dayClassificationFromCampsiteStatuses(
    date: String,
    statuses: Map<Long, AvailabilityStatus>,
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
        availableCampsiteIds = availableIds,
        campsiteStatuses = sorted,
    )
}

fun dayClassificationFromStatuses(
    date: String,
    statuses: List<AvailabilityStatus>,
): DayClassification {
    val keyedStatuses =
        statuses
            .mapIndexed { index, status -> index.toLong() to status }
            .toMap()
    return dayClassificationFromCampsiteStatuses(
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

/**
 * The one window state outside [AvailabilityWindowState]: a per-stream detail
 * the fused campground response has no equivalent for.
 */
private const val ZERO_AVAILABLE_STATE = "zero_available"

/** Roll up per-day classifications into a single window-level state. */
fun classifyWindowState(days: List<DayClassification>): String {
    if (days.none { it.campsiteStatuses.orEmpty().isNotEmpty() }) return AvailabilityWindowState.EMPTY.wireValue
    val allClosed = days.all { it.campsiteStatuses.orEmpty().isNotEmpty() && it.status == AvailabilityStatus.CLOSED }
    val anySuccess =
        days.any {
            it.status == AvailabilityStatus.AVAILABLE ||
                it.status == AvailabilityStatus.FIRST_COME ||
                it.status == AvailabilityStatus.UNKNOWN
        }
    val allReserved = days.all { it.campsiteStatuses.orEmpty().isNotEmpty() && it.status == AvailabilityStatus.RESERVED }
    return when {
        allClosed -> AvailabilityWindowState.CLOSED_FOR_SEASON.wireValue
        anySuccess -> AvailabilityWindowState.SUCCESS.wireValue
        allReserved -> ZERO_AVAILABLE_STATE
        else -> AvailabilityWindowState.SUCCESS.wireValue
    }
}

/** The season block as the wire carries it; null stays absent rather than `{}`. */
fun seasonElement(block: AvailabilitySeasonBlock?): JsonElement? = block?.let { seasonBlockJson.encodeToJsonElement(it) }

/** One cell per campsite, in ascending id order, watchable on status alone. */
private fun statusOnlyCells(statuses: Map<Long, AvailabilityStatus>): Map<Long, AvailabilityCellDto> =
    statuses.toSortedMap().mapValues { (_, status) -> AvailabilityCellDto(status = status, watchable = status.watchable) }

/**
 * `provider` is the vendor id. `season` is an optional reopen-date hint only
 * rec.gov surfaces today. `scope_ref` is the serialized `BookingProviderRef`
 * the observations were fetched under, and is opaque to clients.
 */
fun availabilityResponseDto(
    provider: String,
    startDate: LocalDate,
    endDate: LocalDate,
    perDay: List<DayClassification>,
    state: String,
    seasonBlock: AvailabilitySeasonBlock?,
    cacheBlock: AvailabilityCacheBlock,
    scopeRef: String? = null,
    campsiteId: Long? = null,
): AvailabilityResponseDto =
    AvailabilityResponseDto(
        provider = provider,
        scopeRef = scopeRef,
        campsiteId = campsiteId,
        checkedAt = Instant.now().toString(),
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        state = state,
        season = seasonElement(seasonBlock) ?: JsonNull,
        availability =
            perDay.map { day ->
                val cells = statusOnlyCells(day.campsiteStatuses.orEmpty())
                AvailabilityDayDto(
                    date = day.date,
                    status = day.status,
                    watchable = cells.values.any { it.watchable },
                    cells = cells,
                )
            },
        cache = cacheBlock,
    )

fun availabilityErrorDto(
    error: String,
    detail: String? = null,
    upstreamStatus: Int? = null,
    earliestDate: String? = null,
    timeZone: String? = null,
    latestDate: String? = null,
    maxDays: Int? = null,
): AvailabilityErrorDto =
    AvailabilityErrorDto(
        error = error,
        detail = detail,
        upstreamStatus = upstreamStatus,
        earliestDate = earliestDate,
        timeZone = timeZone,
        latestDate = latestDate,
        maxDays = maxDays,
    )
