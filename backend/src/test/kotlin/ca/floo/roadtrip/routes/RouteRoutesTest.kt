package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.RouteLeg
import ca.floo.roadtrip.client.RouteResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteRoutesTest {
    @Test
    fun `route response serializes feature collection with dto`() {
        val payload =
            routeResponseFeatureCollectionJson(
                response =
                    RouteResponse(
                        coordinates = listOf(listOf(-123.1, 49.28), listOf(-122.33, 47.61)),
                        distanceMeters = 1000.0,
                        durationSeconds = 90.0,
                        legs = listOf(RouteLeg(distanceMeters = 1000.0, durationSeconds = 90.0)),
                    ),
                waypoints = listOf(-123.1 to 49.28, -122.33 to 47.61),
            )
        val json = Json.parseToJsonElement(payload).jsonObject
        val routeFeature = json["features"]!!.jsonArray.single().jsonObject
        val properties = routeFeature["properties"]!!.jsonObject
        val waypoints = properties["waypoints"]!!.jsonArray

        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        assertEquals("Feature", routeFeature["type"]!!.jsonPrimitive.content)
        assertEquals("LineString", routeFeature["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1000.0, properties["distance_m"]!!.jsonPrimitive.double)
        assertEquals(90.0, properties["duration_s"]!!.jsonPrimitive.double)
        assertEquals(-122.33, waypoints[1].jsonArray[0].jsonPrimitive.double)
    }

    @Test
    fun `route response includes corridor feature when provided`() {
        val payload =
            routeResponseFeatureCollectionJson(
                response =
                    RouteResponse(
                        coordinates = listOf(listOf(-123.1, 49.28), listOf(-122.33, 47.61)),
                        distanceMeters = 1000.0,
                        durationSeconds = 90.0,
                        legs = emptyList(),
                    ),
                waypoints = listOf(-123.1 to 49.28, -122.33 to 47.61),
                corridorRadiusMiles = 5.0,
                corridorPolygonGeoJson = """{"type":"Polygon","coordinates":[]}""",
            )
        val features = Json.parseToJsonElement(payload).jsonObject["features"]!!.jsonArray
        val corridor = features[1].jsonObject

        assertEquals(2, features.size)
        assertEquals("Polygon", corridor["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("corridor", corridor["properties"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(5.0, corridor["properties"]!!.jsonObject["radius_miles"]!!.jsonPrimitive.double)
    }
}
