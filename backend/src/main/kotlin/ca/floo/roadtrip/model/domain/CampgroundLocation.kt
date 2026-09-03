package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Shape of the `campgrounds.location` JSONB column. */
@Serializable
data class CampgroundLocation(
    val latitude: Double,
    val longitude: Double,
    val region: String? = null,
    val country: String? = null,
    val address: JsonObject? = null,
)
