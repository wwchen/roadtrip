package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.api.CorridorFeatureDto
import ca.floo.roadtrip.model.api.CorridorPropertiesDto
import ca.floo.roadtrip.model.api.RouteFeatureCollectionDto
import ca.floo.roadtrip.model.api.RouteFeatureDto
import ca.floo.roadtrip.model.api.RouteLegDto
import ca.floo.roadtrip.model.api.RouteLineGeometryDto
import ca.floo.roadtrip.model.api.RoutePropertiesDto
import ca.floo.roadtrip.model.routing.RoutePlan
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

// Only for embedding each feature as a JsonElement inside the collection DTO;
// HTTP serialization belongs to the route layer. Same two settings as the API
// encoder, so the bytes are identical either way.
@OptIn(ExperimentalSerializationApi::class)
private val routeFeatureJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

internal class RouteResponseMapper {
    fun featureCollection(plan: RoutePlan): RouteFeatureCollectionDto {
        val features =
            mutableListOf(
                routeFeatureJson.encodeToJsonElement(
                    RouteFeatureDto(
                        geometry = RouteLineGeometryDto(coordinates = plan.directions.coordinates),
                        properties =
                            RoutePropertiesDto(
                                distanceMeters = plan.directions.distanceMeters,
                                durationSeconds = plan.directions.durationSeconds,
                                legs =
                                    plan.directions.legs.map { leg ->
                                        RouteLegDto(
                                            distanceMeters = leg.distanceMeters,
                                            durationSeconds = leg.durationSeconds,
                                        )
                                    },
                                waypoints = plan.waypoints.map { (lng, lat) -> listOf(lng, lat) },
                            ),
                    ),
                ),
            )
        val radiusMiles = plan.corridorRadiusMiles
        val corridorGeometry = plan.corridorGeoJson
        if (radiusMiles != null && corridorGeometry != null) {
            features +=
                routeFeatureJson.encodeToJsonElement(
                    CorridorFeatureDto(
                        geometry = corridorGeometry,
                        properties = CorridorPropertiesDto(radiusMiles = radiusMiles),
                    ),
                )
        }
        return RouteFeatureCollectionDto(features = features)
    }
}
