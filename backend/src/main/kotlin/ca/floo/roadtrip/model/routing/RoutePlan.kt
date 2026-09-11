package ca.floo.roadtrip.model.routing

import kotlinx.serialization.json.JsonElement

/**
 * One planned route: the directions it resolved to, the waypoints it was asked
 * for, and the corridor polygon when a radius was requested. The polygon is
 * already parsed — the mapper embeds it rather than re-parsing text.
 */
data class RoutePlan(
    val directions: RouteResponse,
    val waypoints: List<Pair<Double, Double>>,
    val corridorRadiusMiles: Double?,
    val corridorGeoJson: JsonElement?,
)
