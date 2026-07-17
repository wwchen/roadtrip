package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CorridorPropertiesDto(
    val role: String = "corridor",
    @SerialName("radius_miles") val radiusMiles: Double,
)
