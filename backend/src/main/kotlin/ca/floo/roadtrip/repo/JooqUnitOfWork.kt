package ca.floo.roadtrip.repo

import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

/** One open block per thread; the first nested call is already depth 1. */
private const val MAX_RUN_DEPTH = 1

private const val NOT_REENTRANT = "UnitOfWork.run is not reentrant: take repo handles inside the block"

private val runDepth = ThreadLocal.withInitial { 0 }

/**
 * The only transaction that spans repos. A repo may still open one over
 * statements it owns end to end; none of those crosses a repo boundary.
 *
 * [autocommit] is the non-transactional bundle, bound to the pool's own
 * autocommit context. The import is non-transactional by decision (RFC 0004),
 * so its sinks take this explicitly rather than inheriting it from whichever
 * constructor they happened to be handed.
 */
class JooqUnitOfWork(
    private val ctx: DSLContext,
) : UnitOfWork {
    val autocommit: Repos = Repos(ctx)

    /**
     * jOOQ rethrows a [RuntimeException] or an [Error] from the block as
     * itself but wraps anything else in `DataAccessException("Rollback caused")`.
     * The block's own throwable is remembered so that wrapper can be undone
     * without also unwrapping a genuine jOOQ failure, whose cause is the
     * driver's exception rather than the block's.
     */
    override fun <T> run(block: (Repos) -> T): T {
        check(runDepth.get() < MAX_RUN_DEPTH) { NOT_REENTRANT }
        runDepth.set(runDepth.get() + 1)
        var blockFailure: Throwable? = null
        try {
            return ctx.transactionResult { config ->
                try {
                    block(Repos(config.dsl()))
                } catch (e: Throwable) {
                    blockFailure = e
                    throw e
                }
            }
        } catch (e: DataAccessException) {
            throw blockFailure?.takeIf { it === e.cause } ?: e
        } finally {
            runDepth.set(runDepth.get() - 1)
        }
    }
}
