package ca.floo.roadtrip.model.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySeasonBlock(
    @SerialName("reopens_on") val reopensOn: String,
)
