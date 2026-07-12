package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RouteLegDto(
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("duration_s") val durationSeconds: Double,
)
