package ca.floo.roadtrip.models.api.poi

import kotlinx.serialization.Serializable

// /api/pois/search response. One row per match; consumer (the topbar) needs
// just enough to render the dropdown row + drive a flyTo + synthesized
// click. Anything richer can be fetched on click via /api/pois/{id}.
@Serializable
data class PoiSearchHitSchema(
    val id: Long,
    val name: String,
    val category: String,
    val region: String? = null,
    val lng: Double,
    val lat: Double,
)
