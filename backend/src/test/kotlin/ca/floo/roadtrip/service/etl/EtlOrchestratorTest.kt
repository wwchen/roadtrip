package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.models.Address
import ca.floo.roadtrip.models.Poi
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.Upsert
import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// End-to-end: copy a captured envelope into a tempdir-shaped data/raw/<source>/,
// run EtlOrchestrator.runSource(), assert pois rows land. Uses the same
// fixture as PlanetFitnessEtlTest but exercises the full orchestrator path
// (RawCapture lookup → parse → validate → transform → Upsert).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtlOrchestratorTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var rawDir: File
    private lateinit var poiRegistry: PoiRegistry

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
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        poiRegistry = PoiRegistry.load(yamlPath)

        // Mirror data/raw/osm-pf/<ts>.json under a tempdir so the
        // orchestrator can find it via the data_source slug.
        rawDir = Files.createTempDirectory("etl-orch-raw-").toFile()
        val source = File(rawDir, "osm-pf")
        source.mkdirs()
        val src =
            File(
                javaClass.classLoader
                    .getResource("etl-fixtures/osm-pf/sample.json")!!
                    .toURI(),
            )
        src.copyTo(File(source, "2026-06-07T21-47-54Z.json"))
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
        rawDir.deleteRecursively()
    }

    private fun newOrchestrator() = EtlOrchestrator(ctx, rawDir, poiRegistry)

    @Test
    fun `runPoiData(Planet Fitness) parses fixture and upserts rows into pois`() {
        val orch = newOrchestrator()
        val stats = orch.runPoiData("Planet Fitness")

        assertEquals(5, stats.transformed, "fixture has 5 elements, all valid")
        assertEquals(5, stats.upsertResult.seenCount)
        assertEquals(0, stats.upsertResult.sweptCount, "first run, no prior rows to sweep")

        val rowCount =
            ctx.fetchCount(
                POIS,
                POIS.SOURCE.eq("planet-fitness").and(POIS.DELETED_AT.isNull),
            )
        assertEquals(5, rowCount, "expected 5 planet-fitness rows after first import")
    }

    @Test
    fun `re-running runPoiData is idempotent (same row count, no extras)`() {
        val orch = newOrchestrator()
        orch.runPoiData("Planet Fitness")
        val stats2 = orch.runPoiData("Planet Fitness")

        assertEquals(5, stats2.upsertResult.seenCount)
        assertEquals(0, stats2.upsertResult.sweptCount, "re-import sees same rows; sweep is no-op")

        val rowCount =
            ctx.fetchCount(
                POIS,
                POIS.SOURCE.eq("planet-fitness").and(POIS.DELETED_AT.isNull),
            )
        assertEquals(5, rowCount, "re-running must not duplicate rows")
    }

    @Test
    fun `upserted rows carry the right category and country`() {
        val orch = newOrchestrator()
        orch.runPoiData("Planet Fitness")

        val sample =
            ctx
                .select(POIS.NAME, POIS.CATEGORY, POIS.COUNTRY)
                .from(POIS)
                .where(POIS.SOURCE.eq("planet-fitness"))
                .and(POIS.DELETED_AT.isNull)
                .limit(1)
                .fetchOne()
        assertNotNull(sample)
        assertEquals("planet-fitness", sample.value2())
        assertEquals("US", sample.value3())
    }

    @Test
    fun `campground jsonb fields are serialized with dto escaping`() {
        Upsert(ctx).run(
            sources = setOf("provider-ref-test"),
            pois =
                listOf(
                    Poi.Campground(
                        source = "provider-ref-test",
                        sourceId = "quoted-recgov",
                        name = "Quoted RecGov Campground",
                        geomGeoJson = """{"type":"Point","coordinates":[-123.1,49.2]}""",
                        region = "BC",
                        country = "CA",
                        phone = null,
                        address =
                            Address(
                                street = """12 "Quoted" Road""",
                                city = "Vancouver",
                                state = "BC",
                                country = "CA",
                            ),
                        infoUrl = null,
                        fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
                        lastVerified = null,
                        providerRef = ProviderRef.RecGov("""12"345"""),
                        amenities = emptyList(),
                        activities = emptyList(),
                        sites = null,
                        season = null,
                        near = null,
                        photoUrl = null,
                        cellCoverage = null,
                        ratingReviews = null,
                        subcategory = "federal",
                        agency = null,
                    ),
                ),
        )

        val row =
            ctx
                .select(POIS.PROVIDER_REF, POIS.ADDRESS)
                .from(POIS)
                .where(POIS.SOURCE.eq("provider-ref-test"))
                .and(POIS.SOURCE_ID.eq("quoted-recgov"))
                .fetchOne()
        assertNotNull(row)

        val providerRefJson = Json.parseToJsonElement(row.value1()!!.data()).jsonObject
        assertEquals("""12"345""", providerRefJson["recgov_id"]!!.jsonPrimitive.content)

        val addressJson = Json.parseToJsonElement(row.value2()!!.data()).jsonObject
        assertEquals("""12 "Quoted" Road""", addressJson["street"]!!.jsonPrimitive.content)
        assertEquals("Vancouver", addressJson["city"]!!.jsonPrimitive.content)
        assertEquals("BC", addressJson["state"]!!.jsonPrimitive.content)
        assertEquals("CA", addressJson["country"]!!.jsonPrimitive.content)
        assertEquals(null, addressJson["postcode"])
    }
}
