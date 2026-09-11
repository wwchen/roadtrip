package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TRANSACTIONAL_SOURCE = "uow-transactional"
private const val ROLLED_BACK_SOURCE = "uow-rolled-back"
private const val AUTOCOMMIT_SOURCE = "uow-autocommit"
private const val SEEN_COUNT = 7

/**
 * The one place a transaction is opened. A block that throws must leave no row
 * behind; the autocommit bundle must leave one immediately.
 */
class JooqUnitOfWorkTest : SharedDbTest() {
    private val unitOfWork by lazy { JooqUnitOfWork(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM import_runs")
    }

    @Test
    fun `a block that returns commits its writes`() {
        val runId = unitOfWork.run { repos -> repos.importRuns.start(TRANSACTIONAL_SOURCE) }

        assertEquals("started", statusOf(runId))
    }

    @Test
    fun `a block that throws rolls its writes back`() {
        assertFailsWith<IllegalStateException> {
            unitOfWork.run { repos ->
                repos.importRuns.start(ROLLED_BACK_SOURCE)
                error("the use case failed after writing")
            }
        }

        assertNull(idOf(ROLLED_BACK_SOURCE), "a failed block must leave no import_runs row")
    }

    @Test
    fun `every repo handle in one block shares the transaction`() {
        assertFailsWith<IllegalStateException> {
            unitOfWork.run { repos ->
                val id = repos.importRuns.start(ROLLED_BACK_SOURCE)
                repos.importRuns.complete(id, seenCount = SEEN_COUNT)
                error("the use case failed after two repo calls")
            }
        }

        assertNull(idOf(ROLLED_BACK_SOURCE), "both writes must roll back together")
    }

    @Test
    fun `autocommit writes are visible immediately, outside any transaction`() {
        val runId = unitOfWork.autocommit.importRuns.start(AUTOCOMMIT_SOURCE)

        assertNotNull(idOf(AUTOCOMMIT_SOURCE))
        assertEquals("started", statusOf(runId))
    }

    @Test
    fun `the autocommit bundle is one instance, and its handles are lazy singletons`() {
        assertEquals(unitOfWork.autocommit, unitOfWork.autocommit)
        assertEquals(unitOfWork.autocommit.importRuns, unitOfWork.autocommit.importRuns)
    }

    private fun statusOf(runId: Long): String? =
        ctx.fetchOne("SELECT status FROM import_runs WHERE id = ?", runId)?.get("status", String::class.java)

    private fun idOf(source: String): Long? =
        ctx.fetchOne("SELECT id FROM import_runs WHERE source = ?", source)?.get("id", Long::class.java)
}
