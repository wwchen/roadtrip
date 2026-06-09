package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.Pois
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals

// Drift check: jOOQ codegen runs against a Testcontainers Postgres at build
// time, so the generated POIS table reflects whichever Flyway migration set
// existed when codegen last ran. If a developer adds V2__*.sql but forgets to
// re-run codegen, the generated classes will be stale and runtime queries will
// silently fail with "column doesn't exist". This test fails the build before
// that happens by comparing the generated column set against a fresh apply of
// every Flyway migration.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JooqCodegenDriftTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource

    @BeforeAll
    fun start() {
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_drift")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                maximumPoolSize = 2
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @Test
    fun `pois columns in Postgres match the generated jOOQ class`() {
        val live =
            ds.connection.use { conn ->
                conn.metaData.getColumns(null, "public", "pois", null).use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getString("COLUMN_NAME"))
                    }
                }
            }
        val generated =
            Pois.POIS
                .fields()
                .map { it.name }
                .toSet()
        assertEquals(live, generated, "jOOQ codegen drift: regenerate via `gradle generateJooq`")
    }
}
