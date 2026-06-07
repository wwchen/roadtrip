package ca.floo.roadtrip.importer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanetFitnessHttpSourceTest {
    // Recorded shape from a real Overpass response, trimmed to two locations:
    // a node (lat/lon direct) and a way (lat/lon under "center"). Tags carry
    // the address bits PlanetFitnessSource downstream relies on.
    private val overpassResponse =
        """
        {
          "version": 0.6,
          "elements": [
            {
              "type": "node",
              "id": 1234567890,
              "lat": 40.7589,
              "lon": -73.9851,
              "tags": {
                "brand": "Planet Fitness",
                "name": "Planet Fitness Times Square",
                "addr:housenumber": "150",
                "addr:street": "W 42nd St",
                "addr:city": "New York",
                "addr:state": "NY",
                "addr:postcode": "10036",
                "phone": "+1-212-555-0100",
                "website": "https://planetfitness.com/gyms/times-square-ny",
                "opening_hours": "24/7"
              }
            },
            {
              "type": "way",
              "id": 9876543210,
              "center": { "lat": 34.0522, "lon": -118.2437 },
              "tags": {
                "brand": "Planet Fitness",
                "name": "Planet Fitness Downtown LA",
                "addr:city": "Los Angeles",
                "addr:state": "CA"
              }
            }
          ]
        }
        """.trimIndent()

    private fun mockClient() =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content = overpassResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    @Test
    fun `fetch writes a FeatureCollection with _fetched_at and one Feature per element`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val source = PlanetFitnessHttpSource()
        val out = source.fetch(mockClient(), tmp.toFile())

        assertEquals(2, out.featureCount)
        assertEquals("planet-fitness.geojson", out.outputFile.name)

        val root = Json.parseToJsonElement(out.outputFile.readText()).jsonObject
        assertEquals("FeatureCollection", root["type"]!!.jsonPrimitive.content)
        // ISO-8601 UTC stamp present and in the right shape.
        val stamp = root["_fetched_at"]!!.jsonPrimitive.content
        assertTrue(stamp.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")), "got: $stamp")

        val features = root["features"]!!.jsonArray
        assertEquals(2, features.size)

        // Node carries lat/lon directly.
        val node = features[0].jsonObject
        assertEquals("Feature", node["type"]!!.jsonPrimitive.content)
        val nodeCoords = node["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(-73.9851, nodeCoords[0].jsonPrimitive.content.toDouble())
        assertEquals(40.7589, nodeCoords[1].jsonPrimitive.content.toDouble())
        assertEquals("Planet Fitness Times Square", node["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("150 W 42nd St", node["properties"]!!.jsonObject["street"]!!.jsonPrimitive.content)

        // Way uses center.lat/lon. Address bits absent → empty string per Python parity.
        val way = features[1].jsonObject
        val wayCoords = way["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(-118.2437, wayCoords[0].jsonPrimitive.content.toDouble())
        assertEquals("", way["properties"]!!.jsonObject["postcode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the output round-trips through PlanetFitnessSource`(
        @TempDir tmp: Path,
    ) = runBlocking {
        // The whole point of writing a file rather than yielding rows: the
        // existing PlanetFitnessSource keeps reading data/planet-fitness.geojson
        // unchanged. Verify the on-disk shape still produces StagedPois.
        val source = PlanetFitnessHttpSource()
        source.fetch(mockClient(), tmp.toFile())

        val staged = PlanetFitnessSource(File(tmp.toFile(), "planet-fitness.geojson")).staged().toList()
        assertEquals(2, staged.size)
        assertNotNull(staged[0].sourceId)
        assertEquals(Category.PLANET_FITNESS, staged[0].category)
    }
}
