package ca.floo.roadtrip.models.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class PointGeometrySchema(
    val type: String = "Point",
    val coordinates: List<Double>,
)
