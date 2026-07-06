package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class RouteFeatureCollectionDto(
    val type: String = "FeatureCollection",
    val features: List<JsonElement>,
)

@Serializable
internal data class RouteFeatureDto(
    val type: String = "Feature",
    val geometry: RouteLineGeometryDto,
    val properties: RoutePropertiesDto,
)

@Serializable
internal data class CorridorFeatureDto(
    val type: String = "Feature",
    val geometry: JsonElement,
    val properties: CorridorPropertiesDto,
)

@Serializable
internal data class RouteLineGeometryDto(
    val type: String = "LineString",
    val coordinates: List<List<Double>>,
)

@Serializable
internal data class RoutePropertiesDto(
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("duration_s") val durationSeconds: Double,
    val legs: List<RouteLegDto>,
    val waypoints: List<List<Double>>,
)

@Serializable
internal data class RouteLegDto(
    @SerialName("distance_m") val distanceMeters: Double,
    @SerialName("duration_s") val durationSeconds: Double,
)

@Serializable
internal data class CorridorPropertiesDto(
    val role: String = "corridor",
    @SerialName("radius_miles") val radiusMiles: Double,
)

@Serializable
internal data class RouteErrorDto(
    val error: String,
    val detail: String,
)
