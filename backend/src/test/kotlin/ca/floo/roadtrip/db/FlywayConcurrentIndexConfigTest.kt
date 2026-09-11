package ca.floo.roadtrip.db

import ca.floo.roadtrip.fixtures.repoFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TRANSACTIONAL_LOCK_KEY = "flyway.postgresql.transactional.lock"
private const val SCRIPT_CONFIG_RESOURCE = "db/migration/V61__booking_alias_indexes.sql.conf"
private const val EXECUTE_IN_TRANSACTION_OFF = "executeInTransaction=false"
private const val BUILD_SCRIPT = "backend/build.gradle.kts"
private const val JOOQ_TASK = "tasks.named<JooqGenerate>(\"generateJooq\")"
private const val FLYWAY_BLOCK = "\nflyway {"
private const val JOOQ_LOCK_SITE = ".configuration(flywaySessionLock)"
private const val PLUGIN_LOCK_SITE = "pluginConfiguration = mapOf(flywaySessionLockKey to \"false\")"

/**
 * The two settings V61's `CREATE INDEX CONCURRENTLY` needs. Neither fails loudly
 * when dropped in a refactor — the migration simply blocks forever on Flyway's
 * own idle-in-transaction connection — so they are asserted rather than trusted.
 */
class FlywayConcurrentIndexConfigTest {
    @Test
    fun `migrate takes a session-level Flyway lock, never a transactional one`() =
        assertEquals(
            "false",
            flywaySessionLock[TRANSACTIONAL_LOCK_KEY],
            "Db.migrate must pass $TRANSACTIONAL_LOCK_KEY=false; a transactional lock deadlocks V61",
        )

    @Test
    fun `the alias index migration ships a script config that leaves the transaction`() {
        val conf =
            checkNotNull(javaClass.classLoader.getResourceAsStream(SCRIPT_CONFIG_RESOURCE)) {
                "$SCRIPT_CONFIG_RESOURCE is missing; Flyway would run V61 inside a transaction"
            }.bufferedReader()
                .readText()

        assertTrue(
            conf.lineSequence().any { it.trim() == EXECUTE_IN_TRANSACTION_OFF },
            "$SCRIPT_CONFIG_RESOURCE must contain $EXECUTE_IN_TRANSACTION_OFF",
        )
    }

    /**
     * The two Gradle sites of the same session lock: the `generateJooq`
     * migration that feeds codegen, and the `flyway { }` block behind
     * `flywayMigrate`. Either one left on the transactional default hangs on
     * V61 in CI or on an operator's machine rather than in the boot path.
     */
    @Test
    fun `both Gradle Flyway sites set the session lock`() {
        val script = repoFile(BUILD_SCRIPT).readText()
        val lockKey = TRANSACTIONAL_LOCK_KEY.removePrefix("flyway.")

        assertTrue(
            script.contains("val flywaySessionLockKey = \"$lockKey\""),
            "$BUILD_SCRIPT must name $lockKey once, for both Flyway sites to share",
        )
        // `substringAfter` returns the whole script when the anchor is gone, so a
        // renamed site would pass against the rest of the file. Anchors first.
        assertTrue(script.contains(JOOQ_TASK), "$BUILD_SCRIPT must still configure $JOOQ_TASK")
        assertTrue(script.contains(FLYWAY_BLOCK), "$BUILD_SCRIPT must still declare a flyway { } block")
        assertTrue(
            script.substringAfter(JOOQ_TASK).substringBefore(FLYWAY_BLOCK).contains(JOOQ_LOCK_SITE),
            "generateJooq's Flyway must pass the session lock via $JOOQ_LOCK_SITE",
        )
        assertTrue(
            script.substringAfter(FLYWAY_BLOCK).substringBefore("\n}").contains(PLUGIN_LOCK_SITE),
            "the flyway { } block must set $PLUGIN_LOCK_SITE",
        )
    }
}
