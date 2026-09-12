package ca.floo.roadtrip.service.etl.vendors.aspira

/** An indexed point and the geometry input slug that supplied it. */
data class GeometryPoint(
    val latitude: Double,
    val longitude: Double,
    val source: String,
)
