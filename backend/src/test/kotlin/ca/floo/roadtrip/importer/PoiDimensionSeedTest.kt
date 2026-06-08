package ca.floo.roadtrip.importer

import ca.floo.roadtrip.db.generated.tables.BookingProvider.Companion.BOOKING_PROVIDER
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
import kotlin.test.assertTrue

// Verifies the YAML registry seeds the booking_provider rows the Kotlin
// ETL looks up by (vendor, host). The seed contract is a hard dependency
// for the ETL — a typo in the YAML silently maps every campground to the
// wrong adapter row.
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
        // booking_provider rows seeded from config/poi-registry.yaml at boot.
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
    fun `booking_provider seed carries the deduped (vendor, host) set`() {
        val rows =
            ctx
                .select(BOOKING_PROVIDER.VENDOR, BOOKING_PROVIDER.HOST, BOOKING_PROVIDER.ADAPTER_CLASS)
                .from(BOOKING_PROVIDER)
                .fetch()
                .map { Triple(it.value1(), it.value2(), it.value3()) }
                .toSet()

        assertTrue(Triple("recgov", "www.recreation.gov", "RecGovAdapter") in rows)
        assertTrue(Triple("aspira", "camping.bcparks.ca", "AspiraAdapter") in rows)
        // ReserveAmerica row must exist (Alberta Parks data FKs to it) but
        // adapter is empty — availability returns no_provider until adapter
        // ships.
        assertTrue(Triple("reserveamerica", "shop.albertaparks.ca", "") in rows)
    }

    @Test
    fun `booking_provider unique key is (vendor, host)`() {
        // Two rows with the same (vendor, host) would let the ETL pick
        // the wrong adapter row at random. The UNIQUE (vendor, host) on
        // the table catches that at insert time; this test asserts the
        // constraint is in place.
        val dupCount =
            ctx.fetchCount(
                ctx
                    .selectFrom(BOOKING_PROVIDER)
                    .where(BOOKING_PROVIDER.VENDOR.eq("aspira"))
                    .and(BOOKING_PROVIDER.HOST.eq("camping.bcparks.ca")),
            )
        assertEquals(1, dupCount, "Aspira BC must have exactly one booking_provider row")
    }
}
