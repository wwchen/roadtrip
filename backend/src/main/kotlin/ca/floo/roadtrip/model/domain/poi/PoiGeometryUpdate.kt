package ca.floo.roadtrip.model.domain.poi

internal data class PoiGeometryUpdate(
    val poiId: Long,
    val longitude: Double,
    val latitude: Double,
)
