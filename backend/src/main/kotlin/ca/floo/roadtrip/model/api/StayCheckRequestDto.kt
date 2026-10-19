package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A stay to check at many campgrounds, answered as a stream: one
 * [StayAnswerDto] per POI as it lands, then a [StayCheckDoneDto].
 */
@Serializable
data class StayCheckRequestDto(
    /**
     * The campgrounds to check. POIs start in the order given, so send the nearest
     * first: with more of them than the server reads at once, the ones at the front
     * answer first and the rest wait their turn.
     */
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
     * How old a stored observation may be and still answer, in seconds, instead of
     * asking the provider again; a re-check sends a small one. The server clamps it
     * rather than refusing it: up to its floor, so a caller cannot force a refetch of
     * every campground on every request (or ask every provider twice with a
     * double-click), and down to its ceiling, which is how stale is too stale to serve
     * at all. Absent means the ceiling, the endpoint's default max age. A smaller age
     * costs an upstream call per campground.
     */
    @SerialName("max_age_seconds") val maxAgeSeconds: Long? = null,
)
