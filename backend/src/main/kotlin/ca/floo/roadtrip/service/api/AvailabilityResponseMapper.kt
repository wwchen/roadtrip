package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.api.AvailabilityCellDto
import ca.floo.roadtrip.model.api.AvailabilityDayDto
import ca.floo.roadtrip.model.api.AvailabilityErrorDto
import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.DayClassification
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One campsite's window as a status per date: the latest observation for each
 * date, `UNKNOWN` where the campsite went unobserved.
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

/**
 * The same rule over one campsite's per-date statuses, where an unobserved date
 * is already `UNKNOWN` and so indistinguishable from an observed one.
 */
internal fun classifyStreamWindowState(statuses: List<AvailabilityStatus>): StreamWindowState = windowState(statuses)

internal fun classifyObservedWindowState(
    statuses: List<AvailabilityStatus>,
    observed: List<Boolean>,
): StreamWindowState = windowState(statuses.zip(observed) { status, seen -> status.takeIf { seen } })

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
