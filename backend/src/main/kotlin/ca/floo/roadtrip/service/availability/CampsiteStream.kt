package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class CampsiteStream(
    val campsiteId: Long,
    val statuses: List<AvailabilityStatus>,
    val observed: List<Boolean>,
)

internal fun streamsFor(
    campsites: List<Campsite>,
    window: ResolvedDateWindow,
    observations: List<CampsiteDayObservation>,
): List<CampsiteStream> {
    val observationsByCampsite = observations.groupBy { it.campsiteId }
    val dayCount = ChronoUnit.DAYS.between(window.startDate, window.endDate).toInt()
    return campsites
        .map { it.id }
        .distinct()
        .sorted()
        .map { campsiteId ->
            val campsiteObservations = observationsByCampsite[campsiteId].orEmpty()
            val observedDates = campsiteObservations.mapTo(mutableSetOf()) { it.date }
            CampsiteStream(
                campsiteId = campsiteId,
                statuses =
                    campsiteWindowStatuses(
                        startDate = window.startDate,
                        endDate = window.endDate,
                        observations = campsiteObservations,
                    ),
                observed = (0 until dayCount).map { offset -> window.startDate.plusDays(offset.toLong()) in observedDates },
            )
        }
}

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
