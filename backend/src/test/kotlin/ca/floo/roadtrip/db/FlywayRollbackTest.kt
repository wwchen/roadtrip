package ca.floo.roadtrip.db

import ca.floo.roadtrip.repo.SharedTestDb
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

private const val RELEASE_MIGRATIONS = "classpath:db/migration"
private const val NEWER_RELEASE_MIGRATIONS = "classpath:db/newer-release"

class FlywayRollbackTest {
    @Test
    fun `a release starts on a schema a newer release has already migrated`() {
        SharedTestDb.createDatabase().use { ds ->
            Flyway
                .configure()
                .configuration(flywayFor(ds).configuration)
                .locations(RELEASE_MIGRATIONS, NEWER_RELEASE_MIGRATIONS)
                .load()
                .migrate()

            assertDoesNotThrow { flywayFor(ds).migrate() }
        }
    }
}
