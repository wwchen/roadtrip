package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.domain.Campsite
import java.time.LocalDate

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
