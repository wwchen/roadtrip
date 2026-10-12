package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityRequestDto(
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    /**
     * The campsites view keeps campsites with a bookable run at least this long. The
     * summary view always asks about every night of the window, so it refuses any
     * value but the default rather than ignore it.
     */
    @SerialName("min_nights") val minNights: Int = 1,
    /** `CampsiteKind` wire values; the route rejects anything else as `bad_request`. */
    @SerialName("site_type") val siteTypes: List<String> = emptyList(),
    /**
     * Only campsites that sleep at least this many count. A campsite with no stated
     * capacity still counts — no data is not a miss, as in the campground filter.
     */
    @SerialName("group_size") val groupSize: Int? = null,
    val view: BulkAvailabilityView = BulkAvailabilityView.CAMPSITES,
)
