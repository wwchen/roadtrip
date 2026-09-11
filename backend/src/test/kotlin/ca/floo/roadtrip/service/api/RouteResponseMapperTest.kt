package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.routing.RouteLeg
import ca.floo.roadtrip.model.routing.RoutePlan
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.route.common.encodeApiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The corridor polygon as PostGIS hands it over: already-encoded GeoJSON text. */
private const val CORRIDOR_POLYGON_JSON =
    """{"type":"Polygon","coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]}"""

/**
 * `/api/route`'s response, byte for byte. The frontend's map layers read every
 * key here; nothing is allowed to move one.
 */
private const val ROUTE_AND_CORRIDOR_JSON =
    """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString",""" +
        """"coordinates":[[-123.1,49.28],[-122.33,47.61]]},"properties":{"distance_m":1000.0,""" +
        """"duration_s":90.0,"legs":[{"distance_m":1000.0,"duration_s":90.0}],""" +
        """"waypoints":[[-123.1,49.28],[-122.33,47.61]]}},{"type":"Feature","geometry":{"type":"Polygon",""" +
        """"coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]},"properties":{"role":"corridor",""" +
        """"radius_miles":5.0}}]}"""

private val directions =
    RouteResponse(
        coordinates = listOf(listOf(-123.1, 49.28), listOf(-122.33, 47.61)),
        distanceMeters = 1000.0,
        durationSeconds = 90.0,
        legs = listOf(RouteLeg(distanceMeters = 1000.0, durationSeconds = 90.0)),
    )

private val waypoints = listOf(-123.1 to 49.28, -122.33 to 47.61)

class RouteResponseMapperTest {
    private val mapper = RouteResponseMapper()

    @Test
    fun `a planned route with a corridor is exactly these bytes`() {
        val plan =
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = 5.0,
                corridorGeoJson = Json.parseToJsonElement(CORRIDOR_POLYGON_JSON),
            )

        assertEquals(ROUTE_AND_CORRIDOR_JSON, encodeApiJson(mapper.featureCollection(plan)))
    }

    @Test
    fun `a route with no corridor carries one feature`() {
        val plan =
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = null,
                corridorGeoJson = null,
            )

        val features =
            Json
                .parseToJsonElement(encodeApiJson(mapper.featureCollection(plan)))
                .jsonObject["features"]!!
                .jsonArray

        assertEquals(1, features.size)
        assertEquals(
            "LineString",
            features
                .single()
                .jsonObject["geometry"]!!
                .jsonObject["type"]!!
                .toString()
                .trim('"'),
        )
    }

    @Test
    fun `a radius with no polygon emits no corridor feature`() {
        // Defensive: the service never produces this pair, and the mapper must
        // not half-emit a corridor if it ever does.
        val plan =
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = 5.0,
                corridorGeoJson = null,
            )

        assertEquals(
            1,
            Json
                .parseToJsonElement(encodeApiJson(mapper.featureCollection(plan)))
                .jsonObject["features"]!!
                .jsonArray.size,
        )
    }
}
