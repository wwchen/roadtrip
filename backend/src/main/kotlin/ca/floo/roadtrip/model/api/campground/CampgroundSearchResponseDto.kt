package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CampgroundSearchResponseDto(
    /** POI ids of the campgrounds inside the boundary that pass the filter, nearest the boundary's centre first. */
    @SerialName("campground_ids") val campgroundIds: List<Long>,
    /** Campgrounds inside the boundary before the filter, so the head can say "9 of 17 in view". */
    @SerialName("total_in_boundary") val totalInBoundary: Int,
    /** Campgrounds that pass the filter before the cap, so the head can say "9 of 17 in view" even when truncated. */
    @SerialName("total_matching") val totalMatching: Int,
    /** True when more passed the filter than `max-results` allows. */
    val truncated: Boolean,
)
