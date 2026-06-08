package ca.floo.roadtrip.etl

import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
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
import java.nio.file.Files
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
        PoiRegistrySync(ctx).apply(PoiRegistry.load(yamlPath))

        // Mirror data/raw/osm-pf/<ts>.json under a tempdir so the orchestrator
        // can find it via newestSingle().
        rawDir = Files.createTempDirectory("etl-orch-").toFile()
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

    @Test
    fun `runSource(osm-pf) parses fixture and upserts rows into pois`() {
        val orch = EtlOrchestrator(ctx, rawDir)
        val stats = orch.runSource("osm-pf")

        assertEquals(5, stats.transformed, "fixture has 5 elements, all valid")
        assertEquals(5, stats.upsertResult.seenCount)
        assertEquals(0, stats.upsertResult.sweptCount, "first run, no prior rows to sweep")

        val rowCount =
            ctx.fetchCount(
                POIS,
                POIS.SOURCE.eq("osm-pf").and(POIS.DELETED_AT.isNull),
            )
        assertEquals(5, rowCount, "expected 5 osm-pf rows after first import")
    }

    @Test
    fun `re-running runSource is idempotent (same row count, no extras)`() {
        val orch = EtlOrchestrator(ctx, rawDir)
        orch.runSource("osm-pf") // first run populates
        val stats2 = orch.runSource("osm-pf") // second run replays

        assertEquals(5, stats2.upsertResult.seenCount)
        assertEquals(0, stats2.upsertResult.sweptCount, "re-import sees same rows; sweep is no-op")

        val rowCount =
            ctx.fetchCount(
                POIS,
                POIS.SOURCE.eq("osm-pf").and(POIS.DELETED_AT.isNull),
            )
        assertEquals(5, rowCount, "re-running must not duplicate rows")
    }

    @Test
    fun `upserted rows carry the right category and country`() {
        val orch = EtlOrchestrator(ctx, rawDir)
        orch.runSource("osm-pf")

        val sample =
            ctx
                .select(POIS.NAME, POIS.CATEGORY, POIS.COUNTRY)
                .from(POIS)
                .where(POIS.SOURCE.eq("osm-pf"))
                .and(POIS.DELETED_AT.isNull)
                .limit(1)
                .fetchOne()
        assertNotNull(sample)
        assertEquals("planet-fitness", sample.value2())
        assertEquals("US", sample.value3())
    }
}
