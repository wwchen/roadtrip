package ca.floo.roadtrip.service.etl.aspira

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

/**
 * Joiner-level test for [AspiraPoiReservableJoiner]. Seeds both `pois`
 * (aspira-{tenant}-pins-shaped rows with source_id = "aspira-{txn}-{map}")
 * and `reservables` carrying the synthetic parent fields
 * AspiraResourcesEtl writes, runs the adapter, asserts the right pairs.
 *
 * One adapter spans every Aspira tenant — the test exercises all three
 * vendors (aspira_wa / aspira_bc / aspira_pc) plus negative cases.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AspiraPoiReservableJoinerTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext
    private lateinit var reservables: ReservableRepo
    private lateinit var joiner: AspiraPoiReservableJoiner

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
        joiner = AspiraPoiReservableJoiner()
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
    fun `links resource to its parent aspira-pc-pins POI`() {
        val poiId = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val resId = upsertResource(vendorId = "501", txnLoc = "1001", mapId = "-2147483640", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(1, links.size)
        assertEquals(resId, links[0].reservableId)
        assertEquals(poiId, links[0].poiId)
    }

    @Test
    fun `links across all three tenants in one pass`() {
        val pcPoi = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        val bcPoi = insertAspiraPoi("aspira-bc-pins", txnLoc = "2001", mapId = "-2147483700")
        val waPoi = insertAspiraPoi("aspira-wa-pins", txnLoc = "3001", mapId = "-2147483800")
        val pcRes = upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")
        val bcRes = upsertResource("601", "2001", "-2147483700", vendor = "aspira_bc")
        val waRes = upsertResource("701", "3001", "-2147483800", vendor = "aspira_wa")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))
        val byReservable = links.associate { it.reservableId to it.poiId }

        assertEquals(3, links.size)
        assertEquals(pcPoi, byReservable[pcRes])
        assertEquals(bcPoi, byReservable[bcRes])
        assertEquals(waPoi, byReservable[waRes])
    }

    @Test
    fun `mismatched txnLoc or mapId yields no link`() {
        // The composite source_id "aspira-{txnLoc}-{mapId}" must match
        // exactly. Stale captures where one half has rotated produce
        // no link — better than a wrong link.
        insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        upsertResource(vendorId = "501", txnLoc = "9999", mapId = "-2147483640", vendor = "aspira_pc")
        upsertResource(vendorId = "502", txnLoc = "1001", mapId = "-9999999999", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    @Test
    fun `non-aspira vendors are ignored`() {
        // A recgov reservable accidentally carrying aspira-shaped raw
        // shouldn't match an Aspira POI.
        insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        upsertResource(vendorId = "fake", txnLoc = "1001", mapId = "-2147483640", vendor = "recgov")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    @Test
    fun `non-aspira POI sources are ignored`() {
        // A POI under federal-campgrounds with a coincidentally
        // matching source_id must NOT pick up Aspira reservables.
        ctx.execute(
            """
            INSERT INTO pois (source, source_id, category, name, geom, fetched_at)
            VALUES ('federal-campgrounds', 'aspira-1001--2147483640', 'campground', 'collide',
                    ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326), now())
            """.trimIndent(),
        )
        upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    @Test
    fun `soft-deleted POIs are not matched`() {
        val poiId = insertAspiraPoi("aspira-pc-pins", txnLoc = "1001", mapId = "-2147483640")
        ctx.execute("UPDATE pois SET deleted_at = now() WHERE id = ?", poiId)
        upsertResource("501", "1001", "-2147483640", vendor = "aspira_pc")

        val links = joiner.discoverLinks(JoinerCtx(ctx = ctx, reservables = reservables))

        assertEquals(0, links.size)
    }

    private fun upsertResource(
        vendorId: String,
        txnLoc: String,
        mapId: String,
        vendor: String,
    ): Long {
        val raw =
            Json.parseToJsonElement(
                """
                {
                  "resource_id":"$vendorId",
                  "_parent_aspira_txn_loc":"$txnLoc",
                  "_parent_aspira_map_id":"$mapId"
                }
                """.trimIndent(),
            )
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

    private fun insertAspiraPoi(
        source: String,
        txnLoc: String,
        mapId: String,
    ): Long =
        ctx
            .resultQuery(
                """
                INSERT INTO pois (
                  source, source_id, category, name, geom, fetched_at
                ) VALUES (
                  ?, ?, 'campground', 'aspira-test',
                  ST_SetSRID(ST_MakePoint(-119.5, 37.7), 4326),
                  now()
                ) RETURNING id
                """.trimIndent(),
                source,
                "aspira-$txnLoc-$mapId",
            ).fetchOne()!!
            .get(0, Long::class.java)!!
}
