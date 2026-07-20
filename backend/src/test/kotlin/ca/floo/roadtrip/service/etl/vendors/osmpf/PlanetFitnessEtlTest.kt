package ca.floo.roadtrip.service.etl.vendors.osmpf

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.RawCaptureStore
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.parsedDto
import ca.floo.roadtrip.service.etl.framework.records
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
// No live DB needed anymore (the legacy provider FK was dropped in V8). The
// real test container in EtlOrchestratorTest exercises Postgres; this
// test is pure Kotlin.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanetFitnessEtlTest {
    private lateinit var transformCtx: TransformCtx

    @BeforeAll
    fun setUp() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        transformCtx = TransformCtx.load(File("build/tmp/etl-test-raw"), registry)
    }

    private fun bundle(envelope: Envelope): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("osm-pf" to listOf(envelope)),
        )

    @Test
    fun `parses captured envelope into DTO with elements`() {
        val envelope = RawCaptureStore.parseEnvelope(fixtureFile())
        assertEquals("fetch_planet_fitness", envelope.fetcher)
        assertEquals(200, envelope.response.status)
        val dto = parsedDto(PlanetFitnessEtl(), bundle(envelope))
        assertEquals(5, dto.elements.size)
    }

    @Test
    fun `parse returns ok for valid payload`() {
        val envelope = RawCaptureStore.parseEnvelope(fixtureFile())
        parsedDto(PlanetFitnessEtl(), bundle(envelope))
    }

    @Test
    fun `transform produces canonical Planet Fitness locations with stable location ids`() {
        val envelope = RawCaptureStore.parseEnvelope(fixtureFile())
        val etl = PlanetFitnessEtl()
        val dto = parsedDto(etl, bundle(envelope))
        val locations = records(etl.transform(dto, transformCtx))

        assertEquals(5, locations.size, "fixture has 5 elements, all valid")
        for (p in locations) {
            assertTrue(
                p.locationId.matches(Regex("^(node|way|relation)-\\d+$")),
                "unexpected locationId=${p.locationId}",
            )
            assertEquals("US", p.country)
            assertNotNull(p.latitude)
            assertNotNull(p.longitude)
        }
    }

    @Test
    fun `transform handles missing optional fields gracefully`() {
        val envelope = RawCaptureStore.parseEnvelope(fixtureFile())
        val etl = PlanetFitnessEtl()
        val dto = parsedDto(etl, bundle(envelope))
        val locations = records(etl.transform(dto, transformCtx))

        val withoutPhone = locations.filter { it.phone == null }
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
