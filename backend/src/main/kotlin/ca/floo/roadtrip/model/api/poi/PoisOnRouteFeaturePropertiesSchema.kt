package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class PoisOnRouteFeaturePropertiesSchema(
    val category: String,
    val subcategory: String? = null,
    val agency: String? = null,
)
