package ca.floo.roadtrip.models.domain.poi

/**
 * Slim row shape for the bbox endpoint. Just enough for MapLibre to place
 * and color/filter a pin; richer detail lives behind GET /api/pois/{id}.
 */
data class PoiRow(
    val id: Long,
    val category: String,
    val subcategory: String?,
    val agency: String?,
    val lng: Double,
    val lat: Double,
)
