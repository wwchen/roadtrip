package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.productionTerminalEtlDefinitions
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
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
    ): String = listOf(lon, lat, "", "", name, "", "", "", "", "", "", "", state).joinToString(",")

    private fun envelope(): Envelope =
        Json.decodeFromString<Envelope>(
            """
            {
              "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://uscampgrounds", "method": "GET" },
              "response": { "status": 200 },
              "payload": ${Json.encodeToString(csv)}
            }
            """.trimIndent(),
        )

    private fun index(stateFilter: String?): Map<String, Pair<Double, Double>> {
        val out = mutableMapOf<String, Pair<Double, Double>>()
        UsCampgroundsCsvSource(listOf(envelope()), stateFilter).indexInto(out)
        return out
    }

    @Test
    fun `a state filter keeps the matching state's coordinates`() {
        val byName = index(stateFilter = "WA")
        assertEquals(46.039 to -120.667, byName[normalize("Brooks Memorial")])
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
    fun `no state filter indexes every state, first row winning`() {
        // Tenants outside the US (Parks Canada, BC) declare no filter and must
        // keep the previous nationwide behaviour.
        val byName = index(stateFilter = null)
        assertEquals(43.177 to -101.732, byName[normalize("Brooks Memorial")])
        assertEquals(47.548 to -122.545, byName[normalize("Manchester")])
    }

    /**
     * The half that actually broke: `state_filter: WA` sat in the registry and
     * nothing read it. Driving the real YAML through the real registry is what
     * catches that — a test against the source alone passes either way.
     */
    @Test
    fun `the WA terminal reads state_filter from the registry`() {
        val definition =
            productionTerminalEtlDefinitions["aspira-wa-campgrounds"]
                ?: error("aspira-wa-campgrounds is not a registered terminal")
        val etl = definition.etl as AspiraCampgroundsEtl

        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-wa" to listOf(jsonEnvelope("[]")),
                    "uscampgrounds" to listOf(envelope()),
                    "aspira-inventory-wa" to listOf(jsonEnvelope("[]")),
                    "aspira-dictionaries-wa" to listOf(jsonEnvelope("{}")),
                ),
            )

        val byName = mutableMapOf<String, Pair<Double, Double>>()
        etl.geometrySourcesFor(inputs).forEach { (_, source) -> source.indexInto(byName) }

        assertEquals(46.039 to -120.667, byName[normalize("Brooks Memorial")])
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
