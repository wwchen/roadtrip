package ca.floo.roadtrip.models.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class PoisOnRouteFeatureSchema(
    val type: String = "Feature",
    val id: Long,
    val geometry: PointGeometrySchema,
    val properties: PoisOnRouteFeaturePropertiesSchema,
)
