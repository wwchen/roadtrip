package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// /api/pois/on-route request body. Same {waypoints, radius_miles}
// shape the trip planner already uses; categories optional and
// defaults to all enabled poi_data categories on the server.
@Serializable
data class PoisOnRouteRequestSchema(
    val waypoints: List<WaypointSchema>,
    @SerialName("radius_miles") val radiusMiles: Double,
    val categories: List<String>? = null,
)
