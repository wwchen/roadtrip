package ca.floo.roadtrip.service.etl.recgov

import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.service.etl.JoinerCtx
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Joiner-level test for [RecgovPoiReservableJoiner]. Seeds both `pois`
 * (federal-campgrounds-shaped rows) and `reservables` (with the
 * synthetic `_parent_facility_id` field RecGovCampsitesEtl writes), runs
 * the adapter, asserts the right pairs come back.
 *
 * Doesn't use Upsert / EtlOrchestrator — writes the rows directly via SQL
 * so the test pins the joiner's match rule, not the upstream wiring.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecgovPoiReservableJoinerTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var reservables: ReservableRepo
    private lateinit var joiner: RecgovPoiReservableJoiner

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
        joiner = RecgovPoiReservableJoiner()
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun reset() {
        ctx.execute("TRUNCATE reservable_pois, reservables, pois RESTART IDENTITY CASCADE")
    }

    @Test
    fun `links campsite to its parent federal-campgrounds POI`() {
        val poiId = insertFederalCampgroundPoi("Upper Pines", facilityId = "232447")
        val resId = upsertCampsite(vendorId = "330257", parentFacilityId = "232447")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(1, links.size)
        assertEquals(resId, links[0].reservableId)
        assertEquals(poiId, links[0].poiId)
    }

    @Test
    fun `every campsite under a facility links to that one POI`() {
        val poiId = insertFederalCampgroundPoi("Upper Pines", facilityId = "232447")
        val r1 = upsertCampsite(vendorId = "330257", parentFacilityId = "232447")
        val r2 = upsertCampsite(vendorId = "330258", parentFacilityId = "232447")
        val r3 = upsertCampsite(vendorId = "330259", parentFacilityId = "232447")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(3, links.size)
        assertEquals(setOf(r1, r2, r3), links.map { it.reservableId }.toSet())
        assertTrue(links.all { it.poiId == poiId }, "all reservables map to the single facility POI")
    }

    @Test
    fun `reservables in different facilities map to different POIs`() {
        val upperPines = insertFederalCampgroundPoi("Upper Pines", facilityId = "232447")
        val lowerPines = insertFederalCampgroundPoi("Lower Pines", facilityId = "232450")
        val a = upsertCampsite(vendorId = "330257", parentFacilityId = "232447")
        val b = upsertCampsite(vendorId = "440001", parentFacilityId = "232450")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))
        val byReservable = links.associate { it.reservableId to it.poiId }

        assertEquals(upperPines, byReservable[a])
        assertEquals(lowerPines, byReservable[b])
    }

    @Test
    fun `orphan reservable with no matching POI yields no link`() {
        upsertCampsite(vendorId = "999999", parentFacilityId = "lost-facility")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    @Test
    fun `non-recgov vendors are ignored`() {
        // An aspira reservable carrying a fake _parent_facility_id should
        // never match a federal-campgrounds POI. The vendor filter is the
        // backstop against synthetic-key collisions across providers.
        insertFederalCampgroundPoi("Upper Pines", facilityId = "232447")
        upsertCampsite(vendorId = "fake-1", parentFacilityId = "232447", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    @Test
    fun `soft-deleted POIs are not matched`() {
        // Soft-deleted POIs (deleted_at IS NOT NULL) shouldn't pick up new
        // reservable links — that would resurrect dead state.
        val poiId = insertFederalCampgroundPoi("Upper Pines", facilityId = "232447")
        ctx.execute("UPDATE pois SET deleted_at = now() WHERE id = ?", poiId)
        upsertCampsite(vendorId = "330257", parentFacilityId = "232447")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    @Test
    fun `reservable missing _parent_facility_id is not matched`() {
        insertFederalCampgroundPoi("Upper Pines", facilityId = "232447")
        // Upsert with raw=null — no synthetic parent key to match on.
        reservables.upsert(
            ReservableRepo.Input(
                rid = ReservableId(ReservableType.SITE, "recgov", "330257"),
                name = "FS1-20",
                loop = null,
                siteType = null,
                raw = null,
            ),
        )

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    private fun upsertCampsite(
        vendorId: String,
        parentFacilityId: String,
        vendor: String = "recgov",
    ): Long {
        val raw = Json.parseToJsonElement("""{"_parent_facility_id":"$parentFacilityId"}""")
        return reservables.upsert(
            ReservableRepo.Input(
                rid = ReservableId(ReservableType.SITE, vendor, vendorId),
                name = null,
                loop = null,
                siteType = null,
                raw = raw,
            ),
        )
    }

    private fun insertFederalCampgroundPoi(
        name: String,
        facilityId: String,
    ): Long =
        ctx
            .resultQuery(
                """
                INSERT INTO pois (
                  source, source_id, category, name, geom, fetched_at
                ) VALUES (
                  'federal-campgrounds', ?, 'campground', ?,
                  ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326),
                  now()
                ) RETURNING id
                """.trimIndent(),
                "recgov-$facilityId",
                name,
            ).fetchOne()!!
            .get(0, Long::class.java)!!
}
