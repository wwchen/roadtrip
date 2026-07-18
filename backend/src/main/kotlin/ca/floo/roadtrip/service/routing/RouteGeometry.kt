package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.model.api.RouteLineGeometryDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MILES_TO_METERS = 1609.34

private val routeGeometryJson = Json { encodeDefaults = true }

internal fun lineStringGeoJson(coords: List<List<Double>>): String =
    routeGeometryJson.encodeToString(RouteLineGeometryDto(coordinates = coords))

internal fun routeCorridorRadiusMeters(radiusMiles: Double): Double = radiusMiles * MILES_TO_METERS
