package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `POST /api/campgrounds/search` narrows by. Every field mirrors one on
 * [CampgroundSummaryDto]. A campground with no data for a field passes it:
 * only a stated miss drops it (see the spec's "No data is not a miss").
 */
@Serializable
data class CampgroundFilterDto(
    /** A `CampsiteKind` wire value; null means any. */
    @SerialName("site_type") val siteType: String? = null,
    /** People count; passes when `max_people >= group_size` or `max_people` is unknown. */
    @SerialName("group_size") val groupSize: Int? = null,
    /** `AmenityKey` wire values that must not be marked absent. */
    val amenities: List<String> = emptyList(),
)
