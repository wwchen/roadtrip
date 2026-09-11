package ca.floo.roadtrip.repo

import org.jooq.DSLContext

/**
 * The only `transactionResult` call site in the backend.
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

    override fun <T> run(block: (Repos) -> T): T = ctx.transactionResult { config -> block(Repos(config.dsl())) }
}
