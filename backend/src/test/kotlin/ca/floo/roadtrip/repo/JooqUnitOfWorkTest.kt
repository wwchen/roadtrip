package ca.floo.roadtrip.repo

import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val TRANSACTIONAL_SOURCE = "uow-transactional"
private const val ROLLED_BACK_SOURCE = "uow-rolled-back"
private const val AUTOCOMMIT_SOURCE = "uow-autocommit"
private const val NESTED_SOURCE = "uow-nested"
private const val SEEN_COUNT = 7
private const val NOT_REENTRANT = "UnitOfWork.run is not reentrant: take repo handles inside the block"

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
    fun `a checked exception reaches the caller as itself, and its write rolls back`() {
        // jOOQ's own transactionResult would hand the caller
        // DataAccessException("Rollback caused"), which a service that took the
        // port precisely so it never names jOOQ cannot be asked to catch.
        val failure =
            assertFailsWith<IOException> {
                unitOfWork.run { repos ->
                    repos.importRuns.start(ROLLED_BACK_SOURCE)
                    throw IOException("the use case failed after writing")
                }
            }

        assertEquals("the use case failed after writing", failure.message)
        assertNull(idOf(ROLLED_BACK_SOURCE), "a failed block must leave no import_runs row")
    }

    @Test
    fun `a jOOQ failure inside the block reaches the caller as jOOQ's own exception`() {
        // The unwrap keys on the block's own throwable, so a statement that fails
        // in the database keeps its DataAccessException and its SQLException cause.
        val failure =
            assertFailsWith<DataAccessException> {
                unitOfWork.run { repos ->
                    repos.importRuns.start(ROLLED_BACK_SOURCE)
                    ctx.execute("SELECT 1 FROM uow_table_that_does_not_exist")
                }
            }

        assertTrue(failure.cause is SQLException, "the driver's failure must stay the cause")
        assertNull(idOf(ROLLED_BACK_SOURCE), "a failed block must leave no import_runs row")
    }

    @Test
    fun `a nested run is refused, and the outer block rolls back`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                unitOfWork.run { repos ->
                    repos.importRuns.start(ROLLED_BACK_SOURCE)
                    unitOfWork.run { nested -> nested.importRuns.start(NESTED_SOURCE) }
                }
            }

        assertEquals(NOT_REENTRANT, failure.message)
        assertNull(idOf(ROLLED_BACK_SOURCE), "the outer block must roll back")
        assertNull(idOf(NESTED_SOURCE), "the nested block must never have run")
    }

    @Test
    fun `a refused nested run leaves the depth guard clean for the next block`() {
        assertFailsWith<IllegalStateException> {
            unitOfWork.run { unitOfWork.run { repos -> repos.importRuns.start(NESTED_SOURCE) } }
        }

        val runId = unitOfWork.run { repos -> repos.importRuns.start(TRANSACTIONAL_SOURCE) }

        assertEquals("started", statusOf(runId))
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
        assertSame(unitOfWork.autocommit, unitOfWork.autocommit)
        assertSame(unitOfWork.autocommit.importRuns, unitOfWork.autocommit.importRuns)
    }

    private fun statusOf(runId: Long): String? =
        ctx.fetchOne("SELECT status FROM import_runs WHERE id = ?", runId)?.get("status", String::class.java)

    private fun idOf(source: String): Long? =
        ctx.fetchOne("SELECT id FROM import_runs WHERE source = ?", source)?.get("id", Long::class.java)
}
