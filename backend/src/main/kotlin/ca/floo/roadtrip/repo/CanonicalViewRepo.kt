package ca.floo.roadtrip.repo

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

/**
 * Owns the two canonical materialized views ([campground_canonical],
 * [campsite_canonical]) and the one-time re-point of dependent rows that
 * followed a match-group change.
 *
 * Refresh + re-point live together because their semantics are inseparable:
 * `refreshCanonicalViews` publishes the current winner-per-group state, and
 * `repointRepresentatives` folds representative pointers (POIs, watch targets,
 * availability rows) onto those winners so downstream reads see one identity
 * per real-world entity.
 */
class CanonicalViewRepo(
    private val ctx: DSLContext,
) {
    data class RepointStats(
        val poisRepointed: Int,
        val watchTargetsRepointed: Int,
        val availabilityRowsRepointed: Int,
    )

    /**
     * Refreshes both canonical views. Takes an AccessExclusiveLock for the
     * duration of each refresh, so canonical-view readers can block briefly
     * during admin/ETL publication. That tradeoff is intentional: non-concurrent
     * refresh avoids the memory-heavy diff path that can crash Postgres on the
     * full campsite catalog. Both refreshes must succeed — an exception from
     * either propagates.
     */
    fun refreshCanonicalViews() {
        ctx.execute(REFRESH_CAMPGROUND_CANONICAL_SQL)
        ctx.execute(REFRESH_CAMPSITE_CANONICAL_SQL)
    }

    /**
     * Translates rows still pointing at non-winner catalog ids over to the
     * group winner, all inside one transaction:
     *
     *  * `poi_campgrounds` — a POI attached to a non-winner campground is
     *    re-pointed. If the winner already has a POI, the two POIs collapse
     *    to the one with the lower id and the loser is soft-deleted.
     *  * `availability_watch_target` — a watch target on a non-winner campsite
     *    is re-pointed. If the winner is already covered for that watch, the
     *    non-winner target is dropped (it would be redundant).
     *  * `availability` — history rows on a non-winner campsite are
     *    re-pointed. The table has no per-(campsite,date) uniqueness, so
     *    there's nothing to collapse.
     *
     * Idempotent: a second call finds no non-winner references left.
     * Only rows actually `UPDATE`d are counted in [RepointStats].
     */
    fun repointRepresentatives(): RepointStats =
        ctx.transactionResult { cfg ->
            val txn = DSL.using(cfg)
            RepointStats(
                poisRepointed = repointPois(txn),
                watchTargetsRepointed = repointWatchTargets(txn),
                availabilityRowsRepointed = repointAvailability(txn),
            )
        }

    private fun repointPois(txn: DSLContext): Int {
        val plan = txn.fetch(POI_REPOINT_PLAN_SQL)
        var repointed = 0
        for (record in plan) {
            val srcPoiId = record.get("src_poi_id", Long::class.java)
            val srcCampgroundId = record.get("src_campground_id", Long::class.java)
            val winnerCampgroundId = record.get("winner_campground_id", Long::class.java)
            // Re-query live: an earlier iteration in this same transaction may
            // have already re-pointed a sibling non-winner's POI at this
            // winner, so the plan's snapshot value can be stale.
            val existingWinnerPoiId: Long? =
                txn
                    .fetchOne(POI_WINNER_POI_ID_SQL, winnerCampgroundId)
                    ?.get("poi_id", Long::class.java)
            val isCollapse = existingWinnerPoiId != null
            log.info(
                "re-point poi: poi_id={} campground {} -> {} (collapse={})",
                srcPoiId,
                srcCampgroundId,
                winnerCampgroundId,
                isCollapse,
            )
            if (!isCollapse) {
                txn.execute(POI_REPOINT_UPDATE_SQL, winnerCampgroundId, srcCampgroundId)
                repointed += 1
            } else if (srcPoiId < existingWinnerPoiId) {
                // src wins: drop the winner-side pc row, re-point src, soft-delete the loser poi.
                txn.execute(POI_DELETE_LINK_BY_CAMPGROUND_SQL, winnerCampgroundId)
                txn.execute(POI_REPOINT_UPDATE_SQL, winnerCampgroundId, srcCampgroundId)
                txn.execute(POI_SOFT_DELETE_SQL, existingWinnerPoiId)
                repointed += 1
            } else {
                // winner wins: drop src's pc row and soft-delete the src poi.
                txn.execute(POI_DELETE_LINK_BY_CAMPGROUND_SQL, srcCampgroundId)
                txn.execute(POI_SOFT_DELETE_SQL, srcPoiId)
            }
        }
        return repointed
    }

    private fun repointWatchTargets(txn: DSLContext): Int {
        val plan = txn.fetch(WATCH_TARGET_REPOINT_PLAN_SQL)
        var repointed = 0
        for (record in plan) {
            val id = record.get("id", Long::class.java)
            val watchId = record.get("watch_id", Long::class.java)
            val srcCampsiteId = record.get("src_campsite_id", Long::class.java)
            val winnerCampsiteId = record.get("winner_campsite_id", Long::class.java)
            // Re-check live inside the transaction — earlier iterations of this
            // loop may have added coverage the pre-computed plan didn't know
            // about (two targets both pointing at S_B collapse to one on
            // winner S_A after the first UPDATE).
            val winnerAlreadyCovered =
                txn
                    .fetchOne(
                        WATCH_TARGET_WINNER_COVERED_SQL,
                        id,
                        watchId,
                        winnerCampsiteId,
                    )!!
                    .get("covered", Boolean::class.java)
            log.info(
                "re-point watch_target: id={} watch_id={} campsite {} -> {} (collapse={})",
                id,
                watchId,
                srcCampsiteId,
                winnerCampsiteId,
                winnerAlreadyCovered,
            )
            if (winnerAlreadyCovered) {
                txn.execute(WATCH_TARGET_DELETE_BY_ID_SQL, id)
            } else {
                txn.execute(WATCH_TARGET_REPOINT_UPDATE_SQL, winnerCampsiteId, id)
                repointed += 1
            }
        }
        return repointed
    }

    private fun repointAvailability(txn: DSLContext): Int {
        val plan = txn.fetch(AVAILABILITY_REPOINT_PLAN_SQL)
        var repointed = 0
        for (record in plan) {
            val id = record.get("id", Long::class.java)
            val srcCampsiteId = record.get("src_campsite_id", Long::class.java)
            val winnerCampsiteId = record.get("winner_campsite_id", Long::class.java)
            log.info(
                "re-point availability: id={} campsite {} -> {}",
                id,
                srcCampsiteId,
                winnerCampsiteId,
            )
            txn.execute(AVAILABILITY_REPOINT_UPDATE_SQL, winnerCampsiteId, id)
            repointed += 1
        }
        return repointed
    }

    companion object {
        private val log = LoggerFactory.getLogger(CanonicalViewRepo::class.java)

        private const val REFRESH_CAMPGROUND_CANONICAL_SQL =
            "REFRESH MATERIALIZED VIEW campground_canonical"
        private const val REFRESH_CAMPSITE_CANONICAL_SQL =
            "REFRESH MATERIALIZED VIEW campsite_canonical"

        // POI re-point plan: every poi_campgrounds row whose campground_id is a
        // non-winner (i.e. differs from its group's canonical id). The winner
        // side is queried live per row inside the transaction — earlier
        // iterations can add coverage the pre-computed plan wouldn't know
        // about.
        private const val POI_REPOINT_PLAN_SQL = """
            SELECT pc.poi_id        AS src_poi_id,
                   pc.campground_id AS src_campground_id,
                   ccv.id           AS winner_campground_id
            FROM poi_campgrounds pc
            JOIN campgrounds cg           ON cg.id = pc.campground_id
            JOIN campground_canonical ccv ON ccv.group_key = COALESCE(cg.match_group_id, cg.id)
            WHERE pc.campground_id <> ccv.id
            ORDER BY pc.poi_id
        """

        private const val POI_WINNER_POI_ID_SQL =
            "SELECT poi_id FROM poi_campgrounds WHERE campground_id = ? LIMIT 1"

        private const val POI_REPOINT_UPDATE_SQL =
            "UPDATE poi_campgrounds SET campground_id = ?, updated_at = now() WHERE campground_id = ?"

        private const val POI_DELETE_LINK_BY_CAMPGROUND_SQL =
            "DELETE FROM poi_campgrounds WHERE campground_id = ?"

        private const val POI_SOFT_DELETE_SQL =
            "UPDATE pois SET deleted_at = now(), updated_at = now() WHERE id = ? AND deleted_at IS NULL"

        // Watch target re-point plan: every availability_watch_target row whose
        // campsite_id is a non-winner. poi_id-only targets are skipped — those
        // roll up through the POI re-point above via the POI's own re-mapping.
        private const val WATCH_TARGET_REPOINT_PLAN_SQL = """
            SELECT wt.id           AS id,
                   wt.watch_id     AS watch_id,
                   wt.campsite_id  AS src_campsite_id,
                   csc.id          AS winner_campsite_id
            FROM availability_watch_target wt
            JOIN campsites cs           ON cs.id = wt.campsite_id
            JOIN campsite_canonical csc ON csc.group_key = COALESCE(cs.match_group_id, cs.id)
            WHERE wt.campsite_id IS NOT NULL
              AND wt.campsite_id <> csc.id
            ORDER BY wt.id
        """

        private const val WATCH_TARGET_WINNER_COVERED_SQL = """
            SELECT EXISTS (
              SELECT 1 FROM availability_watch_target
               WHERE id <> ?
                 AND watch_id = ?
                 AND campsite_id = ?
            ) AS covered
        """

        private const val WATCH_TARGET_REPOINT_UPDATE_SQL =
            "UPDATE availability_watch_target SET campsite_id = ? WHERE id = ?"

        private const val WATCH_TARGET_DELETE_BY_ID_SQL =
            "DELETE FROM availability_watch_target WHERE id = ?"

        // Availability re-point plan: every availability row whose campsite_id
        // is a non-winner. availability has no per-(campsite,date) unique
        // constraint (multiple status-run rows can coexist for one cell), so
        // a plain UPDATE suffices — no collision branch.
        private const val AVAILABILITY_REPOINT_PLAN_SQL = """
            SELECT a.id           AS id,
                   a.campsite_id  AS src_campsite_id,
                   csc.id         AS winner_campsite_id
            FROM availability a
            JOIN campsites cs           ON cs.id = a.campsite_id
            JOIN campsite_canonical csc ON csc.group_key = COALESCE(cs.match_group_id, cs.id)
            WHERE a.campsite_id <> csc.id
            ORDER BY a.id
        """

        private const val AVAILABILITY_REPOINT_UPDATE_SQL =
            "UPDATE availability SET campsite_id = ? WHERE id = ?"
    }
}
