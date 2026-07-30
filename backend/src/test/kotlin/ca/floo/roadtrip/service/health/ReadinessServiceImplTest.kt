package ca.floo.roadtrip.service.health

import ca.floo.roadtrip.repo.DatabaseHealthRepo
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadinessServiceImplTest {
    /**
     * A `DSLContext` with no JDBC connection behind it — jOOQ throws
     * `DetachedException` from `fetchOne()`. That is the real unreachable-database
     * path (a throw out of the repo), not a stub of it.
     */
    private fun detachedRepo() = DatabaseHealthRepo(DSL.using(SQLDialect.POSTGRES))

    @Test
    fun `a throwing database probe reports not ready instead of propagating`() {
        val report = ReadinessServiceImpl(detachedRepo()).report()

        assertFalse(report.databaseReachable)
        assertFalse(report.isReady, "an unreachable database must not be handed traffic")
    }

    @Test
    fun `a reachable database is ready`() {
        assertTrue(ReadinessService.Report(databaseReachable = true).isReady)
    }
}
