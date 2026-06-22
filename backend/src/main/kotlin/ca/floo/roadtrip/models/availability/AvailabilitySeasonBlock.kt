package ca.floo.roadtrip.models.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySeasonBlock(
    @SerialName("reopens_on") val reopensOn: String,
)
