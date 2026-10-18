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
)

internal fun streamsFor(
    campsites: List<Campsite>,
    window: ResolvedDateWindow,
    observations: List<CampsiteDayObservation>,
): List<CampsiteStream> {
    val observationsByCampsite = observations.groupBy { it.campsiteId }
    return campsites
        .map { it.id }
        .distinct()
        .sorted()
        .map { campsiteId ->
            CampsiteStream(
                campsiteId = campsiteId,
                statuses =
                    campsiteWindowStatuses(
                        startDate = window.startDate,
                        endDate = window.endDate,
                        observations = observationsByCampsite[campsiteId].orEmpty(),
                    ),
            )
        }
}

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

private val successStatuses: Set<AvailabilityStatus> =
    setOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.FIRST_COME, AvailabilityStatus.UNKNOWN)

internal fun classifyStreamWindowState(statuses: List<AvailabilityStatus>): StreamWindowState =
    when {
        statuses.isEmpty() -> StreamWindowState.EMPTY
        statuses.all { it == AvailabilityStatus.CLOSED } -> StreamWindowState.CLOSED_FOR_SEASON
        statuses.any { it in successStatuses } -> StreamWindowState.SUCCESS
        statuses.all { it == AvailabilityStatus.RESERVED } -> StreamWindowState.ZERO_AVAILABLE
        else -> StreamWindowState.SUCCESS
    }
