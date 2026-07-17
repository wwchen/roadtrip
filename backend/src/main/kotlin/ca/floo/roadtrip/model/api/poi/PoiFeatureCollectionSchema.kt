package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

// /api/pois response. Slim GeoJSON used for viewport map rendering.
@Serializable
data class PoiFeatureCollectionSchema(
    val type: String = "FeatureCollection",
    val truncated: Boolean,
    val features: List<SlimPoiFeatureSchema>,
)
