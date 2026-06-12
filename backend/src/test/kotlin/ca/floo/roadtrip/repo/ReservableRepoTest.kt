package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DB-backed tests for the reservables catalog. Asserts:
 *   - upsert is idempotent (same composite identity → same row, fields refreshed)
 *   - findByRid round-trips a row inserted via upsert
 *   - findByPoi joins through the N:M link table and filters by type
 *   - countByPoi mirrors findByPoi but returns just the count
 *   - poiIdsForReservable returns active parent POIs for detail responses
 *   - linkToPoi/unlinkFromPoi are idempotent
 *   - one reservable can belong to multiple POIs (the N:M shape)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservableRepoTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var repo: ReservableRepo

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
        repo = ReservableRepo(ctx)
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun resetTables() {
        ctx.execute("TRUNCATE reservable_pois, reservables RESTART IDENTITY CASCADE")
        // pois has FK references; clear it too so tests can insert fresh POIs.
        ctx.execute("TRUNCATE pois RESTART IDENTITY CASCADE")
    }

    @Test
    fun `upsert + findByRid round-trips`() {
        val rid = ReservableId(ReservableType.SITE, "recgov", "330257")
        val rawJson = Json.parseToJsonElement("""{"campsite_id":"330257","loop":"AREA WHITE RIVER"}""")
        val id =
            repo.upsert(
                ReservableRepo.Input(
                    rid = rid,
                    name = "FS1-20",
                    loop = "AREA WHITE RIVER",
                    siteType = "STANDARD NONELECTRIC",
                    raw = rawJson,
                ),
            )

        val found = repo.findByRid(rid)
        assertNotNull(found)
        assertEquals(id, found.id)
        assertEquals(rid, found.rid)
        assertEquals("FS1-20", found.name)
        assertEquals("AREA WHITE RIVER", found.loop)
        assertEquals("STANDARD NONELECTRIC", found.siteType)
        assertEquals(rawJson, found.raw)
    }

    @Test
    fun `upsert is idempotent and refreshes mutable fields`() {
        val rid = ReservableId(ReservableType.SITE, "recgov", "330257")
        val firstId = repo.upsert(ReservableRepo.Input(rid, "FS1-20", "loop A", "STANDARD", null))
        val secondId = repo.upsert(ReservableRepo.Input(rid, "FS1-20-renamed", "loop A", "TENT ONLY", null))

        assertEquals(firstId, secondId, "upsert on same composite must reuse the row")
        val found = repo.findByRid(rid)!!
        assertEquals("FS1-20-renamed", found.name)
        assertEquals("TENT ONLY", found.siteType)
    }

    @Test
    fun `findByRid returns null for unknown composite`() {
        val rid = ReservableId(ReservableType.SITE, "recgov", "999999")
        assertNull(repo.findByRid(rid))
    }

    @Test
    fun `findByPoi returns linked reservables filtered by type`() {
        val poiId = insertCampgroundPoi("Upper Pines")
        val ridA = ReservableId(ReservableType.SITE, "recgov", "1001")
        val ridB = ReservableId(ReservableType.SITE, "recgov", "1002")
        val a = repo.upsert(ReservableRepo.Input(rid = ridA, name = "A1", loop = null, siteType = null, raw = null))
        val b = repo.upsert(ReservableRepo.Input(rid = ridB, name = "B1", loop = null, siteType = null, raw = null))
        repo.linkToPoi(a, poiId)
        repo.linkToPoi(b, poiId)

        val found = repo.findByPoi(poiId, ReservableType.SITE)
        assertEquals(setOf("A1", "B1"), found.map { it.name }.toSet())
    }

    @Test
    fun `findByPoi returns empty for POI with no reservables`() {
        val poiId = insertCampgroundPoi("Empty Campground")
        assertEquals(emptyList(), repo.findByPoi(poiId, ReservableType.SITE))
    }

    @Test
    fun `countByPoi matches findByPoi size`() {
        val poiId = insertCampgroundPoi("Lower Pines")
        repeat(3) { i ->
            val rid = ReservableId(ReservableType.SITE, "recgov", "200$i")
            val id = repo.upsert(ReservableRepo.Input(rid, "site$i", null, null, null))
            repo.linkToPoi(id, poiId)
        }
        assertEquals(3, repo.countByPoi(poiId, ReservableType.SITE))
    }

    @Test
    fun `linkToPoi is idempotent`() {
        val poiId = insertCampgroundPoi("Test CG")
        val rid = ReservableId(ReservableType.SITE, "recgov", "5000")
        val id = repo.upsert(ReservableRepo.Input(rid, "S1", null, null, null))
        repo.linkToPoi(id, poiId)
        repo.linkToPoi(id, poiId) // second link is a no-op via ON CONFLICT DO NOTHING

        assertEquals(1, repo.countByPoi(poiId, ReservableType.SITE))
    }

    @Test
    fun `unlinkFromPoi removes the link only`() {
        val poiId = insertCampgroundPoi("Test CG")
        val rid = ReservableId(ReservableType.SITE, "recgov", "5001")
        val id = repo.upsert(ReservableRepo.Input(rid, "S1", null, null, null))
        repo.linkToPoi(id, poiId)
        assertEquals(1, repo.countByPoi(poiId, ReservableType.SITE))

        repo.unlinkFromPoi(id, poiId)
        assertEquals(0, repo.countByPoi(poiId, ReservableType.SITE))
        // The reservable row itself stays — the link is what we removed.
        assertNotNull(repo.findByRid(rid))
    }

    @Test
    fun `one reservable belongs to multiple POIs`() {
        // The N:M shape: a campsite at Upper Pines is also "in" Yosemite NP
        // when the park POI exists. v1 doesn't ingest park POIs but the schema
        // already supports the relationship; this test pins the behavior.
        val campgroundPoi = insertCampgroundPoi("Upper Pines")
        val parkPoi = insertCampgroundPoi("Yosemite NP")
        val rid = ReservableId(ReservableType.SITE, "recgov", "7000")
        val id = repo.upsert(ReservableRepo.Input(rid, "A1", null, null, null))

        repo.linkToPoi(id, campgroundPoi)
        repo.linkToPoi(id, parkPoi)

        assertEquals(1, repo.countByPoi(campgroundPoi, ReservableType.SITE))
        assertEquals(1, repo.countByPoi(parkPoi, ReservableType.SITE))
        assertEquals(listOf(campgroundPoi, parkPoi).sorted(), repo.poiIdsForReservable(id))
    }

    /**
     * Insert a minimal campground POI for testing. Uses raw SQL because we
     * don't want to depend on the full PoiRepo wiring for a schema test.
     * Returns the new pois.id.
     */
    private fun insertCampgroundPoi(name: String): Long =
        ctx
            .resultQuery(
                """
                INSERT INTO pois (
                  source, source_id, category, name, geom, fetched_at
                ) VALUES (
                  'test', ?, 'campground', ?,
                  ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326),
                  now()
                ) RETURNING id
                """.trimIndent(),
                java.util.UUID
                    .randomUUID()
                    .toString(),
                name,
            ).fetchOne()!!
            .get(0, Long::class.java)!!
}
