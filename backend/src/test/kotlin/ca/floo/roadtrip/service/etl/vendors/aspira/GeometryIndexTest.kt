package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

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
    ): Envelope = rawGeoJson(name, lon.toString(), lat.toString())

    /** Coordinates spliced in verbatim, so a test can seed the literals a feed really ships. */
    private fun rawGeoJson(
        name: String,
        lon: String,
        lat: String,
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

    /** Runs [build] against a private logger so its events can be read back. */
    private fun eventsFrom(build: (org.slf4j.Logger) -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("geometry-index-capture-${System.nanoTime()}") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            build(logger)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

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

    /**
     * A declared source that lands nothing is the shape of a format or
     * `name_property` mismatch, and of upstream renaming a column. It used to
     * read as an ordinary INFO line beside the healthy sources.
     */
    @Test
    fun `a declared source that contributes no keys warns, naming the slug`() {
        var index: Map<String, GeometryPoint> = emptyMap()
        val events =
            eventsFrom { logger ->
                index =
                    GeometryIndex.build(
                        listOf(
                            // The feed names its features `name`; this source was declared to read `Name_fr`.
                            "apca-accommodation" to
                                GeoJsonFeaturesSource(listOf(geoJson("Two Jack Lakeside", -115.49, 51.22)), "Name_fr"),
                            "apca-places" to GeoJsonFeaturesSource(listOf(geoJson("Banff", -115.57, 51.18))),
                        ),
                        logger,
                        ETL_SLUG,
                    )
            }

        assertEquals(setOf(normalize("Banff")), index.keys)
        val warnings = events.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertEquals(1, warnings.size, "exactly the empty source warns: $warnings")
        assertTrue(warnings.single().contains("slug=apca-accommodation"), warnings.single())
    }

    @Test
    fun `non-finite and off-globe coordinates are dropped and counted in the source's log line`() {
        var index: Map<String, GeometryPoint> = emptyMap()
        val events =
            eventsFrom { logger ->
                index =
                    GeometryIndex.build(
                        listOf(
                            "poisoned" to
                                GeoJsonFeaturesSource(
                                    listOf(
                                        rawGeoJson("Poison Park", "-120.5", "\"NaN\""),
                                        rawGeoJson("Inf Park", "\"Infinity\"", "47.1"),
                                        rawGeoJson("Off Globe Park", "-120.5", "91.0"),
                                        rawGeoJson("Wrapped Park", "181.0", "47.1"),
                                        rawGeoJson("Real Park", "-120.5", "47.1"),
                                    ),
                                ),
                        ),
                        logger,
                        ETL_SLUG,
                    )
            }

        assertEquals(mapOf(normalize("Real Park") to GeometryPoint(47.1, -120.5, "poisoned")), index)
        val info = events.single { it.level == Level.INFO }.formattedMessage
        assertTrue(info.contains("dropped 4 off-earth points"), info)
    }
}
