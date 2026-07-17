package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
internal data class RouteLineGeometryDto(
    val type: String = "LineString",
    val coordinates: List<List<Double>>,
)
