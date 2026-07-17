package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RoutePropertiesDto(
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("duration_s") val durationSeconds: Double,
    val legs: List<RouteLegDto>,
    val waypoints: List<List<Double>>,
)
