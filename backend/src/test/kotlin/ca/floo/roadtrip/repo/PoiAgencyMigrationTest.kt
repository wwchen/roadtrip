package ca.floo.roadtrip.repo

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoiAgencyMigrationTest {
    private lateinit var ds: HikariDataSource

    @BeforeAll
    fun start() {
        // This test drives Flyway itself (migrate to an intermediate target,
        // seed, then migrate the rest) so it needs its OWN unmigrated DB — it
        // can't share the pre-migrated template. It gets a blank database on the
        // shared container, so the whole suite still runs one Postgres instance.
        ds = SharedTestDb.createEmptyDatabase()
    }

    @AfterAll
    fun stop() {
        ds.close()
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
