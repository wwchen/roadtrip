package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

private const val ETL_SLUG = "geometry-index-test"

/**
 * The merge rule the YAML's `geometry.sources` order encodes: sources are
 * walked in declared order and the first point to claim a normalized name
 * keeps it, so campground-level feeds outrank park centroids. Each point
 * remembers the input slug that supplied it.
 */
class GeometryIndexTest {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun geoJson(
        name: String,
        lon: Double,
        lat: Double,
    ): Envelope =
        Json.decodeFromString<Envelope>(
            """
            {
              "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://geom", "method": "GET" },
              "response": { "status": 200 },
              "payload": {
                "type": "FeatureCollection",
                "features": [
                  { "type": "Feature",
                    "properties": { "name": ${Json.encodeToString(name)} },
                    "geometry": { "type": "Point", "coordinates": [$lon, $lat] } }
                ]
              }
            }
            """.trimIndent(),
        )

    @Test
    fun `the earlier source wins a name both sources carry`() {
        val index =
            GeometryIndex.build(
                listOf(
                    "apca-accommodation" to GeoJsonFeaturesSource(listOf(geoJson("Two Jack Lakeside", -115.49, 51.22))),
                    "apca-places" to GeoJsonFeaturesSource(listOf(geoJson("Two Jack Lakeside Campground", -115.00, 51.00))),
                ),
                log,
                ETL_SLUG,
            )

        assertEquals(GeometryPoint(51.22, -115.49, "apca-accommodation"), index[normalize("Two Jack Lakeside")])
    }

    @Test
    fun `a name only the later source carries is still indexed, tagged with that source`() {
        val index =
            GeometryIndex.build(
                listOf(
                    "apca-accommodation" to GeoJsonFeaturesSource(listOf(geoJson("Two Jack Lakeside", -115.49, 51.22))),
                    "apca-places" to GeoJsonFeaturesSource(listOf(geoJson("Banff National Park of Canada", -115.57, 51.18))),
                ),
                log,
                ETL_SLUG,
            )

        assertEquals(GeometryPoint(51.18, -115.57, "apca-places"), index[normalize("Banff National Park of Canada")])
    }

    @Test
    fun `a feature whose name normalizes to nothing is not indexed`() {
        val index = GeometryIndex.build(listOf("only" to GeoJsonFeaturesSource(listOf(geoJson("park", -1.0, 2.0)))), log, ETL_SLUG)

        assertEquals(emptyMap<String, GeometryPoint>(), index, "\"park\" is pure park-cruft; it must not index under the empty key")
    }
}
