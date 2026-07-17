package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

// /api/pois/on-route response. A slim GeoJSON FeatureCollection intentionally
// missing bbox-only metadata such as truncated.
@Serializable
data class PoisOnRouteResponseSchema(
    val type: String = "FeatureCollection",
    val features: List<PoisOnRouteFeatureSchema>,
)
