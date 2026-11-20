package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.productionTerminalEtlDefinitions
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The uscampgrounds.info index is nationwide, so a tenant that books one state
 * still sees every state's campgrounds as candidate geometry. Names repeat
 * across states — there is a Brooks Memorial in Washington and another in South
 * Dakota — and the index keeps the first row it sees, so without a filter a
 * Washington leaf can inherit South Dakota's coordinates.
 */
class UsCampgroundsCsvSourceTest {
    /** South Dakota first, so `putIfAbsent` would keep it if nothing filtered. */
    private val csv =
        listOf(
            row("-101.732", "43.177", "Brooks Memorial", "SD"),
            row("-120.667", "46.039", "Brooks Memorial", "WA"),
            row("-122.545", "47.548", "Manchester", "WA"),
        ).joinToString("\n")

    // Column 0 is longitude, 1 latitude, 4 the name and 12 the state. The rest
    // are padding: the source skips any row with fewer than 13 columns.
    private fun row(
        lon: String,
        lat: String,
        name: String,
        state: String,
        type: String = "SP",
    ): String = listOf(lon, lat, "", "", name, type, "", "", "", "", "", "", state).joinToString(",")

    private fun envelope(text: String = csv): Envelope =
        Json.decodeFromString<Envelope>(
            """
            {
              "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://uscampgrounds", "method": "GET" },
              "response": { "status": 200 },
              "payload": ${Json.encodeToString(text)}
            }
            """.trimIndent(),
        )

    private fun index(
        stateFilter: String?,
        text: String = csv,
        typeFilter: List<String>? = null,
    ): Map<String, GeometryPoint> =
        GeometryIndex.build(
            listOf("uscampgrounds" to UsCampgroundsCsvSource(listOf(envelope(text)), stateFilter, typeFilter)),
            LoggerFactory.getLogger(javaClass),
            "uscampgrounds-csv-source-test",
        )

    @Test
    fun `a state filter keeps the matching state's coordinates`() {
        val byName = index(stateFilter = "WA")
        assertEquals(GeometryPoint(46.039, -120.667, "uscampgrounds"), byName[normalize("Brooks Memorial")])
    }

    @Test
    fun `a state filter drops other states entirely`() {
        // Not merely outranked — a name only present in another state must not
        // be indexed at all, or a fuzzy match can still reach it.
        val byName = index(stateFilter = "OR")
        assertNull(byName[normalize("Brooks Memorial")])
        assertNull(byName[normalize("Manchester")])
    }

    @Test
    fun `no state filter indexes every state, and a name two distant states share is pinned to neither`() {
        val byName = index(stateFilter = null)
        assertNull(byName[normalize("Brooks Memorial")])
        assertEquals(GeometryPoint(47.548, -122.545, "uscampgrounds"), byName[normalize("Manchester")])
    }

    @Test
    fun `a type filter keeps only the listed agency types, compared trimmed and case-insensitively`() {
        val text =
            listOf(
                row("-123.165", "47.737", "Dosewallips", "WA", "NP"),
                row("-122.902", "47.689", "Dosewallips State Park", "WA", " sp "),
                row("-120.0", "47.0", "Beebe Bridge", "WA", "CP"),
            ).joinToString("\n")
        val byName = index(stateFilter = "WA", text = text, typeFilter = listOf("SP", "cp"))
        assertEquals(GeometryPoint(47.689, -122.902, "uscampgrounds"), byName[normalize("Dosewallips")])
        assertEquals(GeometryPoint(47.0, -120.0, "uscampgrounds"), byName[normalize("Beebe Bridge")])
        assertNull(index(stateFilter = "WA", text = text)[normalize("Dosewallips")])
    }

    /**
     * The half that actually broke: `state_filter: WA` sat in the registry and
     * nothing read it. Driving the real YAML through the real registry is what
     * catches that — a test against the source alone passes either way. The
     * filter now lives at `geometry.sources[0].state`.
     */
    @Test
    fun `the WA terminal reads the geometry source state from the registry`() {
        val definition =
            productionTerminalEtlDefinitions["aspira-wa-campgrounds"]
                ?: error("aspira-wa-campgrounds is not a registered terminal")
        val etl = definition.etl as AspiraCampgroundsEtl

        val entry =
            PoiRegistry
                .loadResource("poi-registry.yaml")
                .poiData
                .flatMap { it.etls }
                .single { it.slug == "aspira-wa-campgrounds" }
        assertEquals("WA", checkNotNull(entry.geometry).sources.single().state)
        assertEquals(listOf("SP", "CP", "SF"), checkNotNull(entry.geometry).sources.single().types)

        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-wa" to listOf(jsonEnvelope("[]")),
                    "uscampgrounds" to listOf(envelope()),
                    "aspira-inventory-wa" to listOf(jsonEnvelope("[]")),
                    "aspira-dictionaries-wa" to listOf(jsonEnvelope("{}")),
                ),
            )

        val byName =
            GeometryIndex.build(
                etl.geometrySourcesFor(inputs),
                LoggerFactory.getLogger(javaClass),
                "aspira-wa-campgrounds",
            )

        assertEquals(GeometryPoint(46.039, -120.667, "uscampgrounds"), byName[normalize("Brooks Memorial")])
    }

    private fun jsonEnvelope(payload: String): Envelope =
        Json.decodeFromString<Envelope>(
            """
            {
              "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://x", "method": "GET" },
              "response": { "status": 200 },
              "payload": $payload
            }
            """.trimIndent(),
        )
}
