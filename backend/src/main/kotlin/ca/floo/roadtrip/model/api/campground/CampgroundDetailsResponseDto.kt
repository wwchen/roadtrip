package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.Serializable

@Serializable
data class CampgroundDetailsResponseDto(
    /** One per known id, in request order; unknown ids are omitted. */
    val campgrounds: List<CampgroundSummaryDto>,
)
