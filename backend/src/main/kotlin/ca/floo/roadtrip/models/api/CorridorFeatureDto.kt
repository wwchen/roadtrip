package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class CorridorFeatureDto(
    val type: String = "Feature",
    val geometry: JsonElement,
    val properties: CorridorPropertiesDto,
)
