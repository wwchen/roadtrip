package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

// /api/pois request body — bbox is required (4 numbers, [w,s,e,n]),
// zoom + categories optional. Corridor filtering lives behind
// POST /api/pois/on-route.
@Serializable
data class PoisRequestSchema(
    val bbox: List<Double>,
    val zoom: Int? = null,
    val categories: List<String>? = null,
)

// /api/pois/on-route request body. Same {waypoints, radius_miles}
// shape the trip planner already uses; categories optional and
// defaults to all enabled poi_data categories on the server.
@Serializable
data class PoisOnRouteRequestSchema(
    val waypoints: List<WaypointSchema>,
    val radius_miles: Double,
    val categories: List<String>? = null,
)

@Serializable
data class WaypointSchema(
    val lat: Double,
    val lng: Double,
)

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

@Serializable
data class PoiSearchResponseSchema(
    val results: List<PoiSearchHitSchema>,
)
