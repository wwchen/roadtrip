package ca.floo.roadtrip.models.domain.poi

data class PoiSearchHit(
    val id: Long,
    val name: String,
    val category: String,
    val region: String?,
    val lng: Double,
    val lat: Double,
)
