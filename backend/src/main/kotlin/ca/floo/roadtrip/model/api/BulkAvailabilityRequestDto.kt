package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityRequestDto(
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    /** Keep only campsites with a bookable run at least this many nights long. */
    @SerialName("min_nights") val minNights: Int = DEFAULT_MIN_NIGHTS,
    /** `CampsiteKind` wire values; the route rejects anything else as `bad_request`. */
    @SerialName("site_type") val siteTypes: List<String> = emptyList(),
    /**
     * Only campsites that sleep at least this many count. A campsite with no stated
     * capacity still counts — no data is not a miss, as in the campground filter.
     */
    @SerialName("group_size") val groupSize: Int? = null,
    /**
     * Ask the providers again unless an observation is only moments old, instead of
     * answering from anything younger than the endpoint's usual tolerance. For a
     * re-check; it costs an upstream call per campground.
     */
    val fresh: Boolean = false,
) {
    companion object {
        /** `min_nights` when a request leaves it out: one night, i.e. no run-length filter. */
        const val DEFAULT_MIN_NIGHTS = 1
    }
}
