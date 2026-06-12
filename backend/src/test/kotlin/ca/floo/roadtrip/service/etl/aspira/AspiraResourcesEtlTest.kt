package ca.floo.roadtrip.service.etl.aspira

import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.service.etl.EtlOrchestrator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end AspiraResourcesEtl test. Captures both inputs the ETL needs:
 *   - aspira-resources-pc/<ts>/leaf-<mapId>.json (multi-part availability)
 *   - aspira-maps-pc/<ts>.json (single-envelope /api/maps tree)
 * …points the orchestrator at the production YAML, runs the import via
 * the reservable_data section, asserts the catalog landed with parent
 * leaf metadata stamped on each row.
 *
 * Fixture has two leaves and 4 total resources:
 *   - mapId -2147483640 ("Tunnel Mountain Village I"): 3 resources
 *   - mapId -2147483641 ("Two Jack Lakeside"): 1 resource
 *
 * POI linking is NOT exercised here — that's the joiner's job (PR 4).
 * This test confirms the ETL emits reservables independently of any POI
 * state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AspiraResourcesEtlTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var reservables: ReservableRepo
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
        reservables = ReservableRepo(ctx)

        rawDir = Files.createTempDirectory("etl-aspira-resources-").toFile()

        // aspira-resources-pc: multi-part. One timestamped dir, one
        // envelope per leaf.
        val resCapture = File(File(rawDir, "aspira-resources-pc"), "2026-09-12T17-00-00Z")
        resCapture.mkdirs()
        copyFixtureTo("aspira-resources/leaf--2147483640.json", File(resCapture, "leaf--2147483640.json"))
        copyFixtureTo("aspira-resources/leaf--2147483641.json", File(resCapture, "leaf--2147483641.json"))

        // aspira-maps-pc: single-file envelope (matches the live fetcher's
        // shape). Orchestrator's auto-detect picks single-file vs dir by
        // inspecting the newest entry.
        val mapsDir = File(rawDir, "aspira-maps-pc")
        mapsDir.mkdirs()
        copyFixtureTo("aspira-resources/maps.json", File(mapsDir, "2026-09-12T17-00-00Z.json"))

        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        poiRegistry = PoiRegistry.load(yamlPath)
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
        rawDir.deleteRecursively()
    }

    @BeforeEach
    fun resetDb() {
        ctx.execute("TRUNCATE reservable_pois, reservables RESTART IDENTITY CASCADE")
    }

    @Test
    fun `imports reservables from every leaf envelope`() {
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        val stats = orch.runReservableData("Parks Canada Aspira Resources")

        assertEquals("aspira-pc-resources", stats.terminalEtlSlug)
        assertEquals(4, stats.parsed)
        assertEquals(4, stats.upserted)
    }

    @Test
    fun `vendor is the per-tenant slug, not the bare aspira string`() {
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Parks Canada Aspira Resources")

        // ReservableId disallows ':' in vendor, so per-tenant vendors use
        // underscore separators.
        val r = reservables.findByRid(ReservableId(ReservableType.SITE, "aspira_pc", "501"))!!
        assertEquals("aspira_pc", r.rid.vendor)
        assertEquals("501", r.rid.vendorId)
    }

    @Test
    fun `resources land with their leaf as loop, name stays null`() {
        // The /api/availability/map response carries no per-resource
        // names. Loop is the parent leaf's title; name remains null
        // until a future ETL pulls richer catalog data.
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Parks Canada Aspira Resources")

        val tunnel = reservables.findByRid(ReservableId(ReservableType.SITE, "aspira_pc", "501"))!!
        assertNull(tunnel.name)
        assertEquals("Tunnel Mountain Village I", tunnel.loop)

        val twoJack = reservables.findByRid(ReservableId(ReservableType.SITE, "aspira_pc", "601"))!!
        assertNull(twoJack.name)
        assertEquals("Two Jack Lakeside", twoJack.loop)
    }

    @Test
    fun `each reservable carries synthetic parent metadata for the joiner`() {
        // The whole point of these synthetic fields: the joiner needs a
        // place to read txnLoc + mapId so it can match against the right
        // parent POI. Without these, the joiner can't link a resource
        // back to its campground.
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Parks Canada Aspira Resources")

        fun rawOf(vendorId: String): JsonObject =
            reservables
                .findByRid(ReservableId(ReservableType.SITE, "aspira_pc", vendorId))!!
                .raw as JsonObject

        val tunnel = rawOf("502")
        assertEquals("502", (tunnel["resource_id"] as JsonPrimitive).content)
        assertEquals("-2147483640", (tunnel["_parent_aspira_map_id"] as JsonPrimitive).content)
        assertEquals("1001", (tunnel["_parent_aspira_txn_loc"] as JsonPrimitive).content)
        assertEquals("9001", (tunnel["_parent_aspira_resource_loc"] as JsonPrimitive).content)
        assertEquals("Tunnel Mountain Village I", (tunnel["_parent_leaf_name"] as JsonPrimitive).content)

        val twoJack = rawOf("601")
        assertEquals("-2147483641", (twoJack["_parent_aspira_map_id"] as JsonPrimitive).content)
        assertEquals("1002", (twoJack["_parent_aspira_txn_loc"] as JsonPrimitive).content)
        assertEquals("Two Jack Lakeside", (twoJack["_parent_leaf_name"] as JsonPrimitive).content)
    }

    @Test
    fun `re-running is idempotent`() {
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Parks Canada Aspira Resources")
        orch.runReservableData("Parks Canada Aspira Resources")

        val total =
            ctx
                .selectCount()
                .from(ca.floo.roadtrip.db.generated.tables.Reservables.RESERVABLES)
                .fetchOne(0, Int::class.java)!!
        assertEquals(4, total, "upsert key (type,vendor,vendor_id) prevents duplicates")
    }

    @Test
    fun `the etl does not touch reservable_pois`() {
        // Section split contract: the reservable ETL has zero POI
        // knowledge. Even with no POIs in the database, the run should
        // succeed and leave reservable_pois empty.
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Parks Canada Aspira Resources")

        val linkCount =
            ctx
                .selectCount()
                .from(ca.floo.roadtrip.db.generated.tables.ReservablePois.RESERVABLE_POIS)
                .fetchOne(0, Int::class.java)!!
        assertEquals(0, linkCount, "AspiraResourcesEtl must never write to reservable_pois")
    }

    @Test
    fun `walks the maps tree directly without referencing aspira-leaves`() {
        // Cross-row etl refs aren't supported by the orchestrator. The
        // resources ETL re-walks the /api/maps capture itself rather than
        // depending on aspira-leaves-pc from the poi_data section. This
        // test verifies the ETL succeeds even when the poi_data section
        // hasn't been run — there's no etl-out/aspira-leaves-pc directory.
        assertTrue(!File(rawDir, "etl-out").exists())

        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Parks Canada Aspira Resources")

        // 503 is the third resource under -2147483640 in the fixture; if
        // the maps walk hadn't run, we'd have no leaf metadata and the
        // loop would be null.
        val r = reservables.findByRid(ReservableId(ReservableType.SITE, "aspira_pc", "503"))!!
        assertEquals("Tunnel Mountain Village I", r.loop)
    }

    private fun copyFixtureTo(
        fixturePath: String,
        dest: File,
    ) {
        val src =
            File(
                javaClass.classLoader
                    .getResource("etl-fixtures/$fixturePath")!!
                    .toURI(),
            )
        src.copyTo(dest, overwrite = true)
    }
}
