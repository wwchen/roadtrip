package ca.floo.roadtrip.models.api.poi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// /api/pois/{id} response. Wide GeoJSON used for pin popups/drawers.
@Serializable
data class PoiDetailFeatureSchema(
    val type: String = "Feature",
    val id: Long,
    val geometry: JsonElement,
    val properties: PoiDetailPropertiesSchema,
)
