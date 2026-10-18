package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.campsiteWindowStatuses

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
