package ca.floo.roadtrip.routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val MAX_ROUTE_WAYPOINTS = 25
internal const val MIN_ROUTE_CORRIDOR_RADIUS_MILES = 1.0
internal const val MAX_ROUTE_CORRIDOR_RADIUS_MILES = 100.0
internal const val MILES_TO_METERS = 1609.34

private val routeGeometryJson = Json { encodeDefaults = true }

@Serializable
private data class LineStringGeometryDto(
    val type: String = "LineString",
    val coordinates: List<List<Double>>,
)

internal fun lineStringGeoJson(coords: List<List<Double>>): String =
    routeGeometryJson.encodeToString(LineStringGeometryDto(coordinates = coords))

internal fun routeCorridorRadiusMeters(radiusMiles: Double): Double = radiusMiles * MILES_TO_METERS
