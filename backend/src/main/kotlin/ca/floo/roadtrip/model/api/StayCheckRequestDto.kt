package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A stay to check at many campgrounds, answered as a stream: one
 * [StayAnswerDto] per POI as it lands, then a [StayCheckDoneDto].
 */
@Serializable
data class StayCheckRequestDto(
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    /** The first night, `YYYY-MM-DD`. */
    @SerialName("start_date") val startDate: String? = null,
    /** The morning you leave, `YYYY-MM-DD`: the stay is every night before it. */
    @SerialName("end_date") val endDate: String? = null,
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
)
