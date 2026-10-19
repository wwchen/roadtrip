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
    /** The first bookable date in the POI's own time zone. */
    val earliestDate: LocalDate,
    /**
     * [earliestDate] plus the serving provider's booking horizon, or null when
     * no provider claims the campground and there is no horizon to state.
     */
    val latestDate: LocalDate?,
    val allCampsites: List<Campsite>,
    val campsites: List<Campsite>,
    val batch: AvailabilityObservationBatch?,
    /**
     * Whether the campground's serving provider can be polled for openings. A
     * property of that one provider, so it is picked once per slice and folded
     * into every cell's `watchable` from here.
     */
    val pollingSupported: Boolean,
)
