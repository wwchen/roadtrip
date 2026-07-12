package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class RouteFeatureCollectionDto(
    val type: String = "FeatureCollection",
    val features: List<JsonElement>,
)
