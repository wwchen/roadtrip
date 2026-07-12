package ca.floo.roadtrip.models.api.poi

import kotlinx.serialization.Serializable

@Serializable
data class PoiSearchResponseSchema(
    val results: List<PoiSearchHitSchema>,
)
