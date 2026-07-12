package ca.floo.roadtrip.models.routing

import kotlinx.serialization.Serializable

/** Driving route. Coordinates are `[[lng,lat], [lng,lat], ...]` GeoJSON-style. */
@Serializable
data class RouteResponse(
    val coordinates: List<List<Double>>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<RouteLeg>,
)
