package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import java.time.LocalDate

/**
 * One campsite's shaped availability, plus the observations it was built from
 * so a caller can derive more (run lengths) without re-filtering the batch.
 */
internal data class CampsiteEnvelope(
    val response: AvailabilityResponseDto,
    val observations: List<CampsiteDayObservation>,
)

/**
 * One POI's resolved availability read, before it is shaped for a specific
 * endpoint. [batch] is null when the POI has no campsites matching the
 * requested site types, in which case only the window is meaningful.
 */
internal data class PoiAvailabilitySlice(
    val poiId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val allCampsites: List<Campsite>,
    val campsites: List<Campsite>,
    val batch: AvailabilityObservationBatch?,
)

/**
 * One envelope per campsite, each narrowed to that campsite's observations.
 * Both read endpoints shape their response from this, so a change to the
 * envelope lands in one place. Empty when no campsite matched the filter.
 */
internal fun PoiAvailabilitySlice.perCampsiteEnvelopes(): List<CampsiteEnvelope> {
    val batch = batch ?: return emptyList()
    return campsites.map { campsite ->
        val forCampsite = batch.observations.filter { it.campsiteId == campsite.id }
        CampsiteEnvelope(
            response =
                availabilityResponseFromObservations(
                    batch.copy(
                        observations = forCampsite,
                        campsiteId = campsite.id,
                        startDate = startDate,
                        endDate = endDate,
                    ),
                ),
            observations = forCampsite,
        )
    }
}
