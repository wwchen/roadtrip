package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class RouteFeatureCollectionDto(
    val type: String = "FeatureCollection",
    val features: List<JsonElement>,
)
