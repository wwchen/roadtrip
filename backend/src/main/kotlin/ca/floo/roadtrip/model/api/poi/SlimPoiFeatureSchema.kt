package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class SlimPoiFeatureSchema(
    val type: String = "Feature",
    val id: Long,
    val geometry: PointGeometrySchema,
    val properties: SlimPoiPropertiesSchema,
)
