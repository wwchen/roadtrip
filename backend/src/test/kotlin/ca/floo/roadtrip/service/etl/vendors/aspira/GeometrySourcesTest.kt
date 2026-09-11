package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.GeometryFormat
import ca.floo.roadtrip.model.metadata.registry.GeometrySourceSpec
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val ETL_SLUG = "geometry-sources-test"
private const val SLUG = "geom"
private const val PARK = "Two Jack Lakeside"
private const val DECOY_PARK = "Lake Louise"
private const val LON = -115.49
private const val LAT = 51.22

/**
 * Two things the format dispatch has to get right, neither visible through a
 * production run until the pins move: each declared [GeometryFormat] reaches
 * the parser that reads it, and the spec's `state` / `name_property` reach the
 * parser that honours them. The `name_property` half is what
 * `ApcaAccommodationSource` became, so [GeoJsonFeaturesSource] is also pinned
 * directly: the key it reads is the declared one, not the default pair.
 */
class GeometrySourcesTest {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun index(source: GeometrySource): Map<String, GeometryPoint> = GeometryIndex.build(listOf(SLUG to source), log, ETL_SLUG)

    private fun point(): GeometryPoint = GeometryPoint(LAT, LON, SLUG)

    private fun envelope(payloadJson: String): Envelope =
        Json.decodeFromString<Envelope>(
            """
            {
              "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://geom", "method": "GET" },
              "response": { "status": 200 },
              "payload": $payloadJson
            }
            """.trimIndent(),
        )

    /** Column 0 is longitude, 1 latitude, 4 the name and 12 the state; the rest is padding. */
    private fun csvEnvelope(state: String): Envelope {
        val row = listOf("$LON", "$LAT", "", "", PARK, "", "", "", "", "", "", "", state).joinToString(",")
        return envelope(Json.encodeToString(row))
    }

    private fun geoJsonEnvelope(propertiesJson: String): Envelope =
        envelope(
            """
            {
              "type": "FeatureCollection",
              "features": [
                { "type": "Feature",
                  "properties": $propertiesJson,
                  "geometry": { "type": "Point", "coordinates": [$LON, $LAT] } }
              ]
            }
            """.trimIndent(),
        )

    private fun forSpec(
        format: GeometryFormat,
        envelopes: List<Envelope>,
        nameProperty: String? = null,
        state: String? = null,
    ): GeometrySource =
        GeometrySources.forSpec(
            GeometrySourceSpec(input = SLUG, format = format, nameProperty = nameProperty, state = state),
            envelopes,
        )

    @Test
    fun `uscampgrounds_csv builds the CSV reader and carries the declared state through`() {
        val source = forSpec(GeometryFormat.USCAMPGROUNDS_CSV, listOf(csvEnvelope(state = "WA")), state = "WA")
        assertIs<UsCampgroundsCsvSource>(source)
        assertEquals(point(), index(source)[normalize(PARK)])

        val filteredOut = forSpec(GeometryFormat.USCAMPGROUNDS_CSV, listOf(csvEnvelope(state = "SD")), state = "WA")
        assertNull(index(filteredOut)[normalize(PARK)], "a row outside the declared state must not be indexed")
    }

    @Test
    fun `bcparks_strapi builds the Strapi reader`() {
        val payload = """{ "data": [ { "protectedAreaName": "$PARK", "latitude": "$LAT", "longitude": "$LON" } ] }"""
        val source = forSpec(GeometryFormat.BCPARKS_STRAPI, listOf(envelope(payload)))

        assertIs<BcParksStrapiSource>(source)
        assertEquals(point(), index(source)[normalize(PARK)])
    }

    @Test
    fun `arcgis_centroids builds the centroid reader`() {
        val payload =
            """
            { "features": [ { "attributes": { "DESC_EN": "$PARK" }, "centroid": { "x": $LON, "y": $LAT } } ] }
            """.trimIndent()
        val source = forSpec(GeometryFormat.ARCGIS_CENTROIDS, listOf(envelope(payload)))

        assertIs<ArcGisCentroidSource>(source)
        assertEquals(point(), index(source)[normalize(PARK)])
    }

    @Test
    fun `geojson_points carries the declared name_property through`() {
        val envelopes = listOf(geoJsonEnvelope("""{ "Name_e": "$PARK" }"""))
        val source = forSpec(GeometryFormat.GEOJSON_POINTS, envelopes, nameProperty = "Name_e")

        assertIs<GeoJsonFeaturesSource>(source)
        assertEquals(point(), index(source)[normalize(PARK)], "the declared property must be the key that is read")
    }

    @Test
    fun `a declared name_property replaces the default keys rather than joining them`() {
        // The one feature carries both, so an appended or prepended nameProperty
        // would still leave `name` live and index the wrong string.
        val envelopes = listOf(geoJsonEnvelope("""{ "name": "$DECOY_PARK", "Name_e": "$PARK" }"""))
        val source = forSpec(GeometryFormat.GEOJSON_POINTS, envelopes, nameProperty = "Name_e")

        val byName = index(source)
        assertEquals(point(), byName[normalize(PARK)], "the declared property wins outright")
        assertNull(byName[normalize(DECOY_PARK)], "a default key must not outrank the declared one")
        assertEquals(1, byName.size)

        // The other half of "replaces": with a property declared, a feature the
        // defaults alone would have named is not named at all.
        val defaultKeyOnly =
            forSpec(
                GeometryFormat.GEOJSON_POINTS,
                listOf(geoJsonEnvelope("""{ "name": "$DECOY_PARK" }""")),
                nameProperty = "Name_e",
            )
        assertEquals(emptyMap<String, GeometryPoint>(), index(defaultKeyOnly), "the defaults must not stay live")
    }

    @Test
    fun `geojson_points without a name_property reads the default properties`() {
        val declared = forSpec(GeometryFormat.GEOJSON_POINTS, listOf(geoJsonEnvelope("""{ "name": "$PARK" }""")))
        assertEquals(point(), index(declared)[normalize(PARK)])

        val nameOnlyUnderNameE = forSpec(GeometryFormat.GEOJSON_POINTS, listOf(geoJsonEnvelope("""{ "Name_e": "$PARK" }""")))
        assertNull(index(nameOnlyUnderNameE)[normalize(PARK)], "the default pair is name/Name; Name_e must not be read")
    }

    @Test
    fun `the default reader does not see a feature named only by a declared property`() {
        val source = GeoJsonFeaturesSource(listOf(geoJsonEnvelope("""{ "Name_e": "$PARK" }""")))

        assertEquals(emptyMap<String, GeometryPoint>(), index(source))
    }

    @Test
    fun `the default reader falls back from name to Name`() {
        val capitalized = GeoJsonFeaturesSource(listOf(geoJsonEnvelope("""{ "Name": "$PARK" }""")))
        assertEquals(point(), index(capitalized)[normalize(PARK)])

        // A blank `name` is not a name: the fallback has to keep looking, or the
        // feature drops out of the index entirely.
        val blankThenCapitalized = GeoJsonFeaturesSource(listOf(geoJsonEnvelope("""{ "name": "   ", "Name": "$PARK" }""")))
        assertEquals(point(), index(blankThenCapitalized)[normalize(PARK)])
    }
}
