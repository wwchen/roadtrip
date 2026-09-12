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
import kotlinx.serialization.json.JsonElement
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

/**
 * One campsite's window as a status per date: the latest observation for each
 * date, `UNKNOWN` where the campsite went unobserved.
 *
 * The slim counterpart to [dayClassificationsFromObservations], for callers that
 * fuse the streams themselves and would throw away everything but the status.
 */
internal fun campsiteWindowStatuses(
    startDate: LocalDate,
    endDate: LocalDate,
    observations: List<CampsiteDayObservation>,
): List<AvailabilityStatus> {
    val latestByDate = HashMap<LocalDate, CampsiteDayObservation>()
    for (observation in observations) {
        if (observation.date.isBefore(startDate) || !observation.date.isBefore(endDate)) continue
        val current = latestByDate[observation.date]
        if (current == null || !observation.observedAt.isBefore(current.observedAt)) {
            latestByDate[observation.date] = observation
        }
    }
    val days = ChronoUnit.DAYS.between(startDate, endDate).toInt()
    return (0 until days).map { offset ->
        latestByDate[startDate.plusDays(offset.toLong())]?.status ?: AvailabilityStatus.UNKNOWN
    }
}

internal fun availabilityResponseFromObservations(
    batch: AvailabilityObservationBatch,
    pollingSupported: Boolean,
    earliestDate: LocalDate,
): AvailabilityResponseDto {
    val perDay = dayClassificationsFromObservations(batch.startDate, batch.endDate, batch.observations)
    val state = classifyWindowState(perDay)
    return availabilityResponseDto(
        provider = batch.provider,
        startDate = batch.startDate,
        endDate = batch.endDate,
        perDay = perDay,
        state = state,
        seasonBlock = batch.seasonBlock.takeIf { state == StreamWindowState.CLOSED_FOR_SEASON },
        cacheBlock = batch.cacheBlock,
        pollingSupported = pollingSupported,
        earliestDate = earliestDate,
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
 * A single stream's window-level outcome.
 *
 * Wider than [AvailabilityWindowState] by one member: `zero_available` is a
 * per-stream detail the fused campground response has no equivalent for, which
 * is why this stays an internal type carrying its own wire string rather than
 * the wire enum.
 */
internal enum class StreamWindowState(
    val wireValue: String,
) {
    SUCCESS("success"),
    EMPTY("empty"),
    CLOSED_FOR_SEASON("closed_for_season"),
    ZERO_AVAILABLE("zero_available"),
}

/** Roll up per-day classifications into a single window-level state. */
internal fun classifyWindowState(days: List<DayClassification>): StreamWindowState =
    windowState(days.map { day -> day.status.takeIf { day.campsiteStatuses.orEmpty().isNotEmpty() } })

/**
 * The same rule over one campsite's per-date statuses, where an unobserved date
 * is already `UNKNOWN` and so indistinguishable from an observed one.
 */
internal fun classifyStreamWindowState(statuses: List<AvailabilityStatus>): StreamWindowState = windowState(statuses)

/** Null is a date nobody observed; the rollup of an observed date is never null. */
private fun windowState(perDay: List<AvailabilityStatus?>): StreamWindowState {
    if (perDay.all { it == null }) return StreamWindowState.EMPTY
    val allObserved = perDay.all { it != null }
    val anySuccess =
        perDay.any {
            it == null ||
                it == AvailabilityStatus.AVAILABLE ||
                it == AvailabilityStatus.FIRST_COME ||
                it == AvailabilityStatus.UNKNOWN
        }
    return when {
        allObserved && perDay.all { it == AvailabilityStatus.CLOSED } -> StreamWindowState.CLOSED_FOR_SEASON
        anySuccess -> StreamWindowState.SUCCESS
        allObserved && perDay.all { it == AvailabilityStatus.RESERVED } -> StreamWindowState.ZERO_AVAILABLE
        else -> StreamWindowState.SUCCESS
    }
}

/** The season block as the wire carries it; null stays absent rather than `{}`. */
fun seasonElement(block: AvailabilitySeasonBlock?): JsonElement? = block?.let { embeddedApiJson.encodeToJsonElement(it) }

/** One cell per campsite, in ascending id order, each through the shared predicate. */
private fun cellsFor(
    date: LocalDate,
    statuses: Map<Long, AvailabilityStatus>,
    pollingSupported: Boolean,
    earliestDate: LocalDate,
): Map<Long, AvailabilityCellDto> =
    statuses.toSortedMap().mapValues { (_, status) ->
        AvailabilityCellDto.of(status, pollingSupported, date, earliestDate)
    }

/**
 * `provider` is the vendor id. `season` is an optional reopen-date hint only
 * rec.gov surfaces today. `scope_ref` is the serialized `BookingProviderRef`
 * the observations were fetched under, and is opaque to clients.
 *
 * [pollingSupported] and [earliestDate] are the two non-status inputs to a
 * cell's watchability; this response covers one campsite, so polling support is
 * a single flag rather than a per-cell lookup.
 */
internal fun availabilityResponseDto(
    provider: String,
    startDate: LocalDate,
    endDate: LocalDate,
    perDay: List<DayClassification>,
    state: StreamWindowState,
    seasonBlock: AvailabilitySeasonBlock?,
    cacheBlock: AvailabilityCacheBlock,
    pollingSupported: Boolean,
    earliestDate: LocalDate,
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
        state = state.wireValue,
        season = seasonElement(seasonBlock),
        availability =
            perDay.map { day ->
                val cells =
                    cellsFor(
                        date = LocalDate.parse(day.date),
                        statuses = day.campsiteStatuses.orEmpty(),
                        pollingSupported = pollingSupported,
                        earliestDate = earliestDate,
                    )
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
