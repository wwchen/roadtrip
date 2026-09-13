package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /api/campgrounds/details`: POI ids, at most `max-detail-ids`. */
@Serializable
data class CampgroundDetailsRequestDto(
    @SerialName("campground_ids") val campgroundIds: List<Long> = emptyList(),
)
