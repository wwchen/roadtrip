package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityWatchTarget.Companion.AVAILABILITY_WATCH_TARGET
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * Owns all SQL for `availability_watch_target` — the set of POIs/reservables
 * a watch covers. A watch's scope is exactly this set; [AvailabilityWatchRepo]
 * delegates all target reads/writes here rather than embedding the join
 * table's SQL inline, so the two repos can't drift on shape.
 */
class AvailabilityWatchTargetRepo(
    private val ctx: DSLContext,
) {
    data class TargetInput(
        val poiId: Long?,
        val reservableId: Long?,
    ) {
        init {
            require((poiId == null) xor (reservableId == null)) {
                "exactly one of poiId/reservableId must be set per target"
            }
        }
    }

    data class WatchTarget(
        val id: Long,
        val watchId: Long,
        val poiId: Long?,
        val reservableId: Long?,
    )

    /**
     * Replaces the entire target set for [watchId] with exactly [targets].
     * Delete-then-insert rather than diffing — target sets are small
     * (typically 1-5 rows) and callers always supply the full desired set,
     * so there is no partial-update case to preserve row identity for.
     *
     * One transaction: `availability_watch_target_prune_empty` (V29) is a
     * DEFERRABLE INITIALLY DEFERRED constraint trigger that prunes a watch
     * once its last target row is gone. Wrapping delete+insert in a single
     * transaction means that check runs at COMMIT against the target set
     * this call actually leaves behind, not the momentarily-empty state
     * between the DELETE and the re-INSERT.
     */
    fun replaceForWatch(
        watchId: Long,
        targets: List<TargetInput>,
    ) {
        require(targets.isNotEmpty()) { "a watch must have at least one target" }
        ctx.transaction { config ->
            val txn = DSL.using(config)
            txn
                .deleteFrom(AVAILABILITY_WATCH_TARGET)
                .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(watchId))
                .execute()
            targets.forEach { t ->
                txn
                    .insertInto(AVAILABILITY_WATCH_TARGET)
                    .set(AVAILABILITY_WATCH_TARGET.WATCH_ID, watchId)
                    .set(AVAILABILITY_WATCH_TARGET.POI_ID, t.poiId)
                    .set(AVAILABILITY_WATCH_TARGET.RESERVABLE_ID, t.reservableId)
                    .execute()
            }
        }
    }

    fun listForWatch(watchId: Long): List<WatchTarget> =
        ctx
            .selectFrom(AVAILABILITY_WATCH_TARGET)
            .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(watchId))
            .orderBy(AVAILABILITY_WATCH_TARGET.ID.asc())
            .fetch { fromRecord(it) }

    fun deleteForWatch(watchId: Long): Int =
        ctx
            .deleteFrom(AVAILABILITY_WATCH_TARGET)
            .where(AVAILABILITY_WATCH_TARGET.WATCH_ID.eq(watchId))
            .execute()

    private fun fromRecord(r: Record): WatchTarget =
        WatchTarget(
            id = r.get(AVAILABILITY_WATCH_TARGET.ID)!!,
            watchId = r.get(AVAILABILITY_WATCH_TARGET.WATCH_ID)!!,
            poiId = r.get(AVAILABILITY_WATCH_TARGET.POI_ID),
            reservableId = r.get(AVAILABILITY_WATCH_TARGET.RESERVABLE_ID),
        )
}
