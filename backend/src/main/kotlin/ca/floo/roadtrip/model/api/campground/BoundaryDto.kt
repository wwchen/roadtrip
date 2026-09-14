package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
enum class GeoJsonBoundaryType {
    @SerialName("Polygon")
    POLYGON,

    @SerialName("MultiPolygon")
    MULTI_POLYGON,
}

/** A GeoJSON Polygon or MultiPolygon. Coordinates stay opaque: the two types nest differently and PostGIS validates them. */
@Serializable
data class BoundaryDto(
    val type: GeoJsonBoundaryType,
    val coordinates: JsonArray,
)
