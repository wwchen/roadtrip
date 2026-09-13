package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Body of `POST /api/campgrounds/search`. [boundary] is a GeoJSON Polygon or MultiPolygon. */
@Serializable
data class CampgroundSearchRequestDto(
    val boundary: JsonObject? = null,
    val filter: CampgroundFilterDto? = null,
)
