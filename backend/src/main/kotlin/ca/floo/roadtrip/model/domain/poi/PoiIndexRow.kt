package ca.floo.roadtrip.model.domain.poi

/**
 * Minimal row from the generic `pois` index used to dispatch a POI to its
 * category owner.
 */
data class PoiIndexRow(
    val id: Long,
    val category: String,
    val lng: Double?,
    val lat: Double?,
    val geomJson: String,
)
