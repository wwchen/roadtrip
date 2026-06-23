package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiAgencyMigrationTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource

    @BeforeAll
    fun start() {
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_agency_migration")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        ds =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    maximumPoolSize = 2
                },
            )
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @Test
    fun `V25 strips legacy agency property after V24 backfills the agency column`() {
        flyway().target("23").load().migrate()
        val ctx = DSL.using(ds, SQLDialect.POSTGRES)
        ctx.execute(
            """
            INSERT INTO pois (
              source, source_id, category, name, geom, properties, fetched_at
            )
            VALUES (
              'migration-test',
              'legacy-agency',
              'campground',
              'Legacy Agency Campground',
              ST_GeomFromText('POINT(-123.1 49.2)', 4326),
              '{"agency":"Legacy Agency","foo":"bar"}'::jsonb,
              NOW()
            )
            """.trimIndent(),
        )
        flyway().load().migrate()

        val row =
            ctx.fetchOne(
                """
                SELECT agency,
                       jsonb_exists(properties, 'agency') AS has_legacy_agency,
                       jsonb_exists(properties, 'foo') AS has_other_property
                FROM pois
                WHERE source = 'migration-test'
                  AND source_id = 'legacy-agency'
                """.trimIndent(),
            )

        assertEquals("Legacy Agency", row!!.get("agency", String::class.java))
        assertFalse(row.get("has_legacy_agency", Boolean::class.java))
        assertTrue(row.get("has_other_property", Boolean::class.java))
    }

    private fun flyway() =
        Flyway
            .configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("0")
}
