package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** The vendor's aggregate visitor rating, under `campgrounds.metadata.rating`. */
@Serializable
data class CampgroundRating(
    val average: Double,
    val count: Int,
)
