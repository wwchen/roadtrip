package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** One entry of the `campgrounds.photos` JSONB array. */
@Serializable
data class CampgroundPhoto(
    val url: String,
)
