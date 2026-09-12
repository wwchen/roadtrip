package ca.floo.roadtrip.service.etl.vendors.aspira

/** A raw name from a geometry feed and the point it names, before normalization. */
data class NamedPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)
