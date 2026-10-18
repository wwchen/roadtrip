package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.campsiteWindowStatuses
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
