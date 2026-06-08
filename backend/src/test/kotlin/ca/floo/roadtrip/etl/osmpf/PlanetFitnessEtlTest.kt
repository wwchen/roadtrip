package ca.floo.roadtrip.etl.osmpf

import ca.floo.roadtrip.etl.RawCapture
import ca.floo.roadtrip.etl.TransformCtx
import ca.floo.roadtrip.etl.ValidationResult
import ca.floo.roadtrip.etl.registry.PoiRegistry
import ca.floo.roadtrip.etl.registry.PoiRegistrySync
import ca.floo.roadtrip.importer.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Golden-file ETL test against a captured raw fixture. The fixture is a
// 5-element slice of a real OSM Overpass capture; if the pipeline drifts
// against this fixture, that's the canary.
//
// We need a live DB only for TransformCtx (booking_provider FK lookups).
// Parse + validate happen in pure Kotlin; transform is mostly pure too,
// but the ctx is plumbed through for ETL implementations that resolve
// per-row booking provider FKs.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanetFitnessEtlTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var transformCtx: TransformCtx

    @BeforeAll
    fun setUp() {
        pg = PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
        pg
            .withDatabaseName("roadtrip")
            .withUsername("test")
            .withPassword("test")
            .start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
        // Dim rows seeded from config/poi-registry.yaml at boot (PR 3.5).
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        val registry = PoiRegistry.load(yamlPath)
        PoiRegistrySync(ctx).apply(registry)
        transformCtx = TransformCtx.load(ctx, File("build/tmp/etl-test-raw"), registry)
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
    }

    @Test
    fun `parses captured envelope into DTO with elements`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        assertEquals("fetch_planet_fitness", envelope.fetcher)
        assertEquals(200, envelope.response.status)
        val dto = PlanetFitnessEtl().parse(envelope)
        assertEquals(5, dto.elements.size)
    }

    @Test
    fun `validate rejects empty payload but accepts valid one`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        val dto = PlanetFitnessEtl().parse(envelope)
        when (val r = PlanetFitnessEtl().validate(dto)) {
            is ValidationResult.Ok -> {} // expected
            is ValidationResult.Bad ->
                throw AssertionError("validate should accept the fixture: ${r.errors}")
        }
    }

    @Test
    fun `transform produces Poi#PlanetFitness with stamped FK and stable source_id`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        val etl = PlanetFitnessEtl()
        val dto = etl.parse(envelope)
        val pois = etl.transform(dto, transformCtx)

        assertEquals(5, pois.size, "fixture has 5 elements, all valid")
        for (p in pois) {
            // sourceId format from the ETL: <type>-<id>
            assertTrue(
                p.sourceId.matches(Regex("^(node|way|relation)-\\d+$")),
                "unexpected sourceId=${p.sourceId}",
            )
            assertEquals("osm-pf", p.source)
            // Continental US bbox in the fetcher → country=US for every row.
            assertEquals("US", p.country)
            assertNotNull(p.geomGeoJson)
            // Geometry is point GeoJSON pointing at lat/lon.
            assertTrue(p.geomGeoJson.contains("\"Point\""))
        }
    }

    @Test
    fun `transform handles missing optional fields gracefully`() {
        val envelope = RawCapture.parseEnvelope(fixtureFile())
        val etl = PlanetFitnessEtl()
        val dto = etl.parse(envelope)
        val pois = etl.transform(dto, transformCtx)

        // Some elements in the fixture won't have phone or website. The
        // transform should yield null for those, not blank strings.
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
