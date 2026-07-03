package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.Pois
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Drift check: jOOQ codegen runs against a Testcontainers Postgres at build
// time, so the generated POIS table reflects whichever Flyway migration set
// existed when codegen last ran. If a developer adds V2__*.sql but forgets to
// re-run codegen, the generated classes will be stale and runtime queries will
// silently fail with "column doesn't exist". This test fails the build before
// that happens by comparing the generated column set against a fresh apply of
// every Flyway migration.
class JooqCodegenDriftTest : SharedDbTest() {
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
