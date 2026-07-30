package ca.floo.roadtrip.repo

import org.jooq.DSLContext

/**
 * Seconds a readiness probe waits for Postgres before giving up. Short on
 * purpose: an orchestrator polls readiness on a tight loop, so a probe that
 * blocks on a wedged pool is itself an outage. Longer than a healthy
 * `SELECT 1` needs by orders of magnitude.
 */
private const val PROBE_TIMEOUT_SECONDS = 2

/**
 * The database's own liveness, for the readiness probe. Deliberately the
 * narrowest possible persistence surface: it owns no entity and answers one
 * question — can we still talk to Postgres?
 */
internal class DatabaseHealthRepo(
    private val ctx: DSLContext,
) {
    /**
     * The cheapest round trip that proves a pooled connection reaches Postgres
     * and gets an answer back: `SELECT 1`. Touches no table, so it stays
     * correct across migrations.
     *
     * Throws (rather than returning `false`) when the pool is exhausted, the
     * server is unreachable, or the query outlives [PROBE_TIMEOUT_SECONDS] —
     * classifying that failure is the caller's policy, not persistence's.
     */
    fun isReachable(): Boolean = ctx.selectOne().queryTimeout(PROBE_TIMEOUT_SECONDS).fetchOne() != null
}
