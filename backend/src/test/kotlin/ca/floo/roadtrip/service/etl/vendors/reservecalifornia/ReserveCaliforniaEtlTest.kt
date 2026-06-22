package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReserveCaliforniaEtlTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var reservablesRepo: ReservableRepo
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
        reservablesRepo = ReservableRepo(ctx)

        rawDir = Files.createTempDirectory("etl-reservecalifornia-").toFile()
        val captureDir = File(File(rawDir, "reservecalifornia-catalog"), "2026-09-12T17-00-00Z")
        captureDir.mkdirs()
        copyFixtureTo("reservecalifornia/place-690.json", File(captureDir, "place-690.json"))
        copyFixtureTo("reservecalifornia/search-all-overlap.json", File(captureDir, "search-all.json"))
        copyFixtureTo("reservecalifornia/facility-612.json", File(captureDir, "facility-612.json"))
        copyFixtureTo("reservecalifornia/grid-612.json", File(captureDir, "grid-612.json"))

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
        ctx.execute("TRUNCATE reservable_pois, reservables, pois RESTART IDENTITY CASCADE")
    }

    @Test
    fun `imports california campground POI with provider ref`() {
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        val stats = orch.runPoiData("California State Parks")

        assertEquals("california-state-parks", stats.terminalEtlSlug)
        assertEquals(1, stats.parsed)

        val row =
            ctx.fetchOne(
                "SELECT source, source_id, name, info_url, provider_ref::text FROM pois WHERE source = ?",
                "california-state-parks",
            )!!
        assertEquals("california-state-parks", row.get(0, String::class.java))
        assertEquals("rc-690", row.get(1, String::class.java))
        assertEquals("Pfeiffer Big Sur SP", row.get(2, String::class.java))
        assertEquals("https://reservecalifornia.com/park/690", row.get(3, String::class.java))

        val ref =
            ca.floo.roadtrip.service.reservation.ProviderRefParser
                .parse(row.get(4, String::class.java)!!)
        val rc = assertIs<ProviderRef.ReserveCalifornia>(ref)
        assertEquals(690L, rc.placeId)
        assertEquals(listOf(612L), rc.facilityIds)
    }

    @Test
    fun `imports reservecalifornia sites and links them to the place POI`() {
        val orch = EtlOrchestrator(ctx, rawDir, poiRegistry)
        orch.runPoiData("California State Parks")
        orch.runReservableData("California State Park Sites")
        val joiner = ReserveCaliforniaPoiReservableJoiner()

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservablesRepo = reservablesRepo))
        reservablesRepo.linkToPois(links.map { ReservableRepo.LinkInput(it.reservableId, it.poiId) })

        val site =
            reservablesRepo.findByRid(
                ReservableId(ReservableType.SITE, "reservecalifornia", "43793"),
            )!!
        assertEquals("Campsite #W079", site.name)
        assertEquals("Weyland Camp (sites 79-130)", site.loop)
        assertEquals("Campsite", site.siteType)
        val raw = site.raw as JsonObject
        assertEquals("690", raw["_parent_place_id"]!!.jsonPrimitive.content)
        assertEquals("612", raw["_parent_facility_id"]!!.jsonPrimitive.content)

        val linked = reservablesRepo.findByPoi(1, ReservableType.SITE)
        assertEquals(listOf("site:reservecalifornia:43793"), linked.map { it.rid.encode() })
    }

    private fun copyFixtureTo(
        fixturePath: String,
        dest: File,
    ) {
        val url =
            javaClass.classLoader.getResource("etl-fixtures/$fixturePath")
                ?: error("missing fixture $fixturePath")
        dest.writeText(File(url.toURI()).readText())
    }
}
