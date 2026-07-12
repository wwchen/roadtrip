package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
internal data class RouteFeatureDto(
    val type: String = "Feature",
    val geometry: RouteLineGeometryDto,
    val properties: RoutePropertiesDto,
)
