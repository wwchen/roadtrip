package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/pois/on-route`.
 *
 * Every field decodes leniently so the route answers a missing or mistyped one
 * with a message naming it, rather than with kotlinx's own exception text. The
 * real bounds live in the route's `validated(...)`, which needs `RouteConfig`.
 */
@Serializable
data class OnRouteRequestDto(
    val waypoints: List<OnRouteWaypointDto> = emptyList(),
    @SerialName("radius_miles") val radiusMiles: Double? = null,
    val categories: List<String>? = null,
)

@Serializable
data class OnRouteWaypointDto(
    val lat: Double? = null,
    val lng: Double? = null,
)
