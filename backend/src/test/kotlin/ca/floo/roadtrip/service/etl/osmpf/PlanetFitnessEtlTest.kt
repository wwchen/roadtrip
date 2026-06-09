package ca.floo.roadtrip.service.etl.osmpf

import ca.floo.roadtrip.models.Envelope
import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.RawCapture
import ca.floo.roadtrip.service.etl.InputBundle
import ca.floo.roadtrip.service.etl.TransformCtx
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Golden-file ETL test against a captured raw fixture. The fixture is a
// 5-element slice of a real OSM Overpass capture; if the pipeline drifts
// against this fixture, that's the canary.
//
// No live DB needed anymore (booking_provider was dropped in V8). The
// real test container in EtlOrchestratorTest exercises Postgres; this
// test is pure Kotlin.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanetFitnessEtlTest {
    private lateinit var transformCtx: TransformCtx

    @BeforeAll
    fun setUp() {
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        val registry = PoiRegistry.load(yamlPath)
        transformCtx = TransformCtx.load(File("build/tmp/etl-test-raw"), registry)
    }

    private fun bundle(envelope: Envelope): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("osm-pf-raw" to listOf(envelope)),
            etlOutputs = linkedMapOf(),
        )

    @Test
    fun `parses captured envelope into DTO with elements`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        assertEquals("fetch_planet_fitness", envelope.fetcher)
        assertEquals(200, envelope.response.status)
        val dto = PlanetFitnessEtl().parse(bundle(envelope))
        assertEquals(5, dto.elements.size)
    }

    @Test
    fun `validate rejects empty payload but accepts valid one`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        val dto = PlanetFitnessEtl().parse(bundle(envelope))
        when (val r = PlanetFitnessEtl().validate(dto)) {
            is ValidationResult.Ok -> {} // expected
            is ValidationResult.Bad ->
                throw AssertionError("validate should accept the fixture: ${r.errors}")
        }
    }

    @Test
    fun `transform produces Poi#PlanetFitness with stable source value and source_id`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        val etl = PlanetFitnessEtl()
        val dto = etl.parse(bundle(envelope))
        val pois = etl.transform(dto, transformCtx)

        assertEquals(5, pois.size, "fixture has 5 elements, all valid")
        for (p in pois) {
            assertTrue(
                p.sourceId.matches(Regex("^(node|way|relation)-\\d+$")),
                "unexpected sourceId=${p.sourceId}",
            )
            assertEquals("osm-pf", p.source)
            assertEquals("US", p.country)
            assertNotNull(p.geomGeoJson)
            assertTrue(p.geomGeoJson.contains("\"Point\""))
        }
    }

    @Test
    fun `transform handles missing optional fields gracefully`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        val etl = PlanetFitnessEtl()
        val dto = etl.parse(bundle(envelope))
        val pois = etl.transform(dto, transformCtx)

        val withoutPhone = pois.filter { it.phone == null }
        assertTrue(
            withoutPhone.isNotEmpty(),
            "expected at least one fixture element without phone (got all-with-phone, fixture is too clean)",
        )
        for (p in withoutPhone) {
            assertEquals(null, p.phone, "phone should be null, not empty string")
        }
    }

    private fun fixtureFile(): File = File(javaClass.classLoader.getResource("etl-fixtures/osm-pf/sample.json")!!.toURI())
}
