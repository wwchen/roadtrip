package ca.floo.roadtrip.importer

import ca.floo.roadtrip.db.generated.tables.BookingProvider.Companion.BOOKING_PROVIDER
import ca.floo.roadtrip.db.generated.tables.GoverningBody.Companion.GOVERNING_BODY
import ca.floo.roadtrip.etl.registry.PoiRegistry
import ca.floo.roadtrip.etl.registry.PoiRegistrySync
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

// Verifies V6 seed loads the expected dimension rows. The Kotlin ETL (PR 3)
// looks up FK ids by `slug` (governing_body) and (vendor, host) (booking_provider),
// so the seed contract is a hard dependency for the ETL — a typo in the migration
// silently maps every campground to the wrong agency.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiDimensionSeedTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

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
        // Dim rows are now seeded from config/poi-registry.yaml at boot
        // (PR 3.5), so the test mirrors the same flow before asserting.
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        PoiRegistrySync(ctx).apply(PoiRegistry.load(yamlPath))
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
    }

    @Test
    fun `governing_body seed contains the agencies the ETL will look up`() {
        val slugs =
            ctx
                .select(GOVERNING_BODY.SLUG)
                .from(GOVERNING_BODY)
                .fetch()
                .intoSet(GOVERNING_BODY.SLUG)
        // Each slug below is a hard contract with a Kotlin ETL transformer.
        // Adding an agency = new row in V6; removing one without retiring its
        // transformer is the failure mode this test catches.
        for (expected in listOf(
            "nps",
            "us-state-park",
            "parks-canada",
            "bc-parks",
            "alberta-parks",
            "tesla",
            "pf",
        )) {
            assertTrue(expected in slugs, "expected governing_body.slug=$expected; have=$slugs")
        }
    }

    @Test
    fun `booking_provider seed has Aspira × 3 hosts and RecGov`() {
        val rows =
            ctx
                .select(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST, BOOKING_PROVIDER.ADAPTER_CLASS)
                .from(BOOKING_PROVIDER)
                .fetch()
                .map { Triple(it.value1(), it.value2(), it.value3()) }
                .toSet()

        assertTrue(Triple("recgov", "www.recreation.gov", "RecGovAdapter") in rows)
        assertTrue(Triple("aspira", "reservation.pc.gc.ca", "AspiraAdapter") in rows)
        assertTrue(Triple("aspira", "camping.bcparks.ca", "AspiraAdapter") in rows)
        assertTrue(Triple("aspira", "washington.goingtocamp.com", "AspiraAdapter") in rows)
        // ReserveAmerica row must exist (Alberta Parks data FKs to it) but
        // adapter is empty — availability returns no_provider until adapter
        // ships. Active Network's ReserveAmerica platform; PR #73 is the
        // first fetcher.
        assertTrue(Triple("reserveamerica", "shop.albertaparks.ca", "") in rows)
    }

    @Test
    fun `booking_provider unique key is (vendor, host)`() {
        // Two Aspira rows with the same host would let the ETL pick the wrong
        // adapter row at random. The UNIQUE (vendor, host) on the table catches
        // that at insert time; this test asserts the constraint is in place.
        val dupCount =
            ctx.fetchCount(
                ctx
                    .selectFrom(BOOKING_PROVIDER)
                    .where(BOOKING_PROVIDER.VENDOR.eq("aspira"))
                    .and(BOOKING_PROVIDER.HOST.eq("reservation.pc.gc.ca")),
            )
        assertEquals(1, dupCount, "Aspira PC must have exactly one booking_provider row")
    }

    @Test
    fun `governing_body kind is restricted to the expected vocabulary`() {
        val kinds =
            ctx
                .select(GOVERNING_BODY.KIND)
                .from(GOVERNING_BODY)
                .fetch()
                .intoSet(GOVERNING_BODY.KIND)
        for (k in kinds) {
            assertNotNull(k)
            assertTrue(
                k in setOf("federal", "state", "provincial", "local", "private", "corporate"),
                "unexpected governing_body.kind=$k (CHECK constraint should have rejected it)",
            )
        }
    }
}
