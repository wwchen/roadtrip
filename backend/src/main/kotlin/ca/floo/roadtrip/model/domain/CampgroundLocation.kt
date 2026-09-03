package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** Shape of the `campgrounds.location` JSONB column. */
@Serializable
data class CampgroundLocation(
    val latitude: Double,
    val longitude: Double,
    val region: String? = null,
    val country: String? = null,
    val elevation: Double? = null,
    val address: Address? = null,
)
