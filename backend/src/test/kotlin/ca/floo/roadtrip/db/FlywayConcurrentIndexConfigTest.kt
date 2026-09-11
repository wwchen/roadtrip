package ca.floo.roadtrip.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TRANSACTIONAL_LOCK_KEY = "flyway.postgresql.transactional.lock"
private const val SCRIPT_CONFIG_RESOURCE = "db/migration/V61__booking_alias_indexes.sql.conf"
private const val EXECUTE_IN_TRANSACTION_OFF = "executeInTransaction=false"

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
}
