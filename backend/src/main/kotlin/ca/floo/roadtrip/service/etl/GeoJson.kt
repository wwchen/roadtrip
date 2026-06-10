package ca.floo.roadtrip.service.etl

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val etlGeoJson =
    Json {
        encodeDefaults = true
    }

internal fun pointGeoJson(
    lng: Double,
    lat: Double,
): String =
    etlGeoJson.encodeToString(
        PointGeometryDto(coordinates = listOf(lng, lat)),
    )

@Serializable
private data class PointGeometryDto(
    val type: String = "Point",
    val coordinates: List<Double>,
)
