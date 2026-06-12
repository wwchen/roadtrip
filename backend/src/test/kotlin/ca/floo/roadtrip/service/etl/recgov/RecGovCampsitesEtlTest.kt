package ca.floo.roadtrip.service.etl.recgov

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

/**
 * End-to-end RecGovCampsitesEtl test. Wires the captured fixture into a
 * tempdir-shaped data/raw/recgov-campsites/<ts>/ tree, points the
 * orchestrator at the production YAML, runs the import via the
 * reservable_data section, and asserts the catalog rows landed.
 *
 * The fixture has two facility envelopes:
 *  - facility-232447.json: 3 campsites (one with null site/loop)
 *  - facility-232450.json: 1 campsite
 *
 * After the run we expect 4 reservables in the catalog. POI linking is
 * NOT exercised here — that's the joiner's job (PR 4). This test
 * confirms the ETL emits reservables independently of any POI state.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecGovCampsitesEtlTest {
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

        rawDir = Files.createTempDirectory("etl-recgov-campsites-").toFile()
        // Mirror the on-disk shape the orchestrator expects:
        //   data/raw/recgov-campsites/<ts>/facility-<id>.json
        // (multi-part capture under a single timestamp directory).
        val captureDir = File(File(rawDir, "recgov-campsites"), "2026-09-12T17-00-00Z")
        captureDir.mkdirs()
        copyFixtureTo("recgov-campsites/facility-232447.json", File(captureDir, "facility-232447.json"))
        copyFixtureTo("recgov-campsites/facility-232450.json", File(captureDir, "facility-232450.json"))

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
    fun `imports reservables from both facilities`() {
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        val stats = orch.runReservableData("Federal Campsites")

        assertEquals("federal-campsites", stats.terminalEtlSlug)
        assertEquals(4, stats.parsed)
        assertEquals(4, stats.upserted)

        // Spot-check the rich row's field mapping.
        val rid330257 = ReservableId(ReservableType.SITE, "recgov", "330257")
        val r = reservables.findByRid(rid330257)!!
        assertEquals("FS1-20", r.name)
        assertEquals("AREA WHITE RIVER", r.loop)
        assertEquals("STANDARD NONELECTRIC", r.siteType)

        // Synthetic _parent_facility_id is injected so the joiner has a
        // place to read it. Without this the joiner has no way to match
        // a reservable back to its parent facility POI.
        val raw = r.raw as JsonObject
        assertEquals(
            "232447",
            (raw["_parent_facility_id"] as JsonPrimitive).content,
            "ETL must inject _parent_facility_id so the joiner can match",
        )

        // Full upstream blob preserved verbatim — attributes round-trip.
        val attrs = raw["attributes"] as kotlinx.serialization.json.JsonArray
        val firePit = attrs[1] as JsonObject
        assertEquals(
            "Yes",
            (firePit["attribute_value"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `null name and loop fields round-trip as null`() {
        // facility-232447.json's third campsite has site=null and loop=null.
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Federal Campsites")

        val r =
            reservables.findByRid(
                ReservableId(ReservableType.SITE, "recgov", "330259"),
            )!!
        assertEquals(null, r.name)
        assertEquals(null, r.loop)
        assertEquals("STANDARD NONELECTRIC", r.siteType)
    }

    @Test
    fun `re-running is idempotent`() {
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Federal Campsites")
        orch.runReservableData("Federal Campsites")

        val total =
            ctx
                .selectCount()
                .from(ca.floo.roadtrip.db.generated.tables.Reservables.RESERVABLES)
                .fetchOne(0, Int::class.java)!!
        assertEquals(4, total, "upsert key (type,vendor,vendor_id) prevents duplicates")
    }

    @Test
    fun `the etl does not touch reservable_pois`() {
        // The whole point of the section split: the reservable ETL has
        // zero POI knowledge. Even with no POIs in the database, the
        // ETL run should succeed and leave reservable_pois empty.
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Federal Campsites")

        val linkCount =
            ctx
                .selectCount()
                .from(ca.floo.roadtrip.db.generated.tables.ReservablePois.RESERVABLE_POIS)
                .fetchOne(0, Int::class.java)!!
        assertEquals(0, linkCount, "RecGovCampsitesEtl must never write to reservable_pois")
    }

    @Test
    fun `each reservable has its own _parent_facility_id matching its envelope`() {
        // The fixture has two envelopes (FacilityID 232447 and 232450).
        // The 3 campsites in 232447 should all carry _parent_facility_id=232447;
        // the 1 campsite in 232450 should carry _parent_facility_id=232450.
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runReservableData("Federal Campsites")

        fun parentOf(vendorId: String): String {
            val r = reservables.findByRid(ReservableId(ReservableType.SITE, "recgov", vendorId))!!
            return ((r.raw as JsonObject)["_parent_facility_id"] as JsonPrimitive).content
        }
        assertEquals("232447", parentOf("330257"))
        assertEquals("232447", parentOf("330258"))
        assertEquals("232447", parentOf("330259"))
        assertEquals("232450", parentOf("440001"))
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
