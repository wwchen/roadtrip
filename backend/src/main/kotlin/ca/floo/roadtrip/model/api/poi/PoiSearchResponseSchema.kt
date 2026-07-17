package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class PoiSearchResponseSchema(
    val results: List<PoiSearchHitSchema>,
)
