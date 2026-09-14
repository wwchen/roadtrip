package ca.floo.roadtrip.model.api.campground

import kotlinx.serialization.Serializable

/** Body of `POST /api/campgrounds/search`. [boundary] is a GeoJSON Polygon or MultiPolygon. */
@Serializable
data class CampgroundSearchRequestDto(
    val boundary: BoundaryDto? = null,
    val filter: CampgroundFilterDto? = null,
)
