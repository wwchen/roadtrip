package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** Shape of the `campgrounds.contact` JSONB column. */
@Serializable
data class CampgroundContact(
    val phone: String,
)
