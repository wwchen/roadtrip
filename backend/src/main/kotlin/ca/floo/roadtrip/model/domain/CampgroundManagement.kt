package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** Shape of the `campgrounds.management` JSONB column. */
@Serializable
data class CampgroundManagement(
    val agency: String,
)
