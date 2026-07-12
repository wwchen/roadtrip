package ca.floo.roadtrip.repo

import org.jooq.DSLContext

/**
 * Owns the two canonical materialized views ([campground_canonical],
 * [campsite_canonical]).
 */
class CanonicalViewRepo(
    private val ctx: DSLContext,
) {
    /**
     * Refreshes both canonical views. Takes an AccessExclusiveLock for the
     * duration of each refresh, so canonical-view readers can block briefly
     * during admin/ETL publication. That tradeoff is intentional: non-concurrent
     * refresh avoids the memory-heavy diff path that can crash Postgres on the
     * full campsite catalog. Both refreshes must succeed; an exception from
     * either propagates.
     */
    fun refreshCanonicalViews() {
        ctx.execute(REFRESH_CAMPGROUND_CANONICAL_SQL)
        ctx.execute(REFRESH_CAMPSITE_CANONICAL_SQL)
    }

    companion object {
        private const val REFRESH_CAMPGROUND_CANONICAL_SQL =
            "REFRESH MATERIALIZED VIEW campground_canonical"
        private const val REFRESH_CAMPSITE_CANONICAL_SQL =
            "REFRESH MATERIALIZED VIEW campsite_canonical"
    }
}
