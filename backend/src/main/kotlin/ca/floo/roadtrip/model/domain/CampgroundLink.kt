package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** One entry of the `campgrounds.links` JSONB array. */
@Serializable
data class CampgroundLink(
    val url: String,
    val title: String? = null,
)
