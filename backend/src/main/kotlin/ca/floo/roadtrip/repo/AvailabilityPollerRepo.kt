package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AvailabilityPoller.Companion.AVAILABILITY_POLLER
import ca.floo.roadtrip.db.generated.tables.AvailabilityWatch.Companion.AVAILABILITY_WATCH
import ca.floo.roadtrip.db.generated.tables.AvailabilityWatchPoller.Companion.AVAILABILITY_WATCH_POLLER
import ca.floo.roadtrip.db.generated.tables.Reservables.Companion.RESERVABLES
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.scheduler.framework.Schedulable
import ca.floo.roadtrip.service.scheduler.framework.SchedulableRepo
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Repo backing `availability_poller` — the coalesced, per-(provider,
 * parent_ref) unit of scheduled vendor work. Many `availability_watch`
 * rows can share one poller via `availability_watch_poller`; the
 * scheduler only ever claims/leases pollers.
 *
 * Claim/release/reclaim use the same `FOR UPDATE SKIP LOCKED` two-step
 * claim as the scheduler framework expects, swapped onto the `active`
 * boolean gate instead of a `status` enum column — pollers don't have a
 * paused state, only active/dormant.
 */
private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500

class AvailabilityPollerRepo(
    private val ctx: DSLContext,
) : SchedulableRepo<AvailabilityPollerRepo.Poller> {
    private val watchRepo = AvailabilityWatchRepo(ctx)

    data class Poller(
        override val id: Long,
        val provider: String,
        val parentRef: String,
        val poiId: Long,
        val active: Boolean,
        val nextRunAt: OffsetDateTime,
        val claimedUntil: OffsetDateTime?,
        override val claimToken: String?,
        val lastRunAt: OffsetDateTime?,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
    ) : Schedulable

    /**
     * Atomically create or revive the poller for (provider, parentRef).
     * Called whenever a watch is linked to this vendor call unit. Re-uses
     * the existing row (UNIQUE(provider, parent_ref)) so coalesced watches
     * always land on the same poller.
     *
     * [pullNextRunAt], when non-null, pulls `next_run_at` earlier — it
     * never pushes it later, so a newly-linked watch with a faster cadence
     * can't be starved by an existing poller's slower schedule. A dormant
     * poller (no links) is revived (`active = true`) and its representative
     * `poi_id` refreshed.
     */
    fun upsertActive(
        provider: String,
        parentRef: String,
        poiId: Long,
        pullNextRunAt: OffsetDateTime?,
    ): Long {
        val now = OffsetDateTime.now()
        val insertNextRun = pullNextRunAt ?: now
        return ctx
            .insertInto(AVAILABILITY_POLLER)
            .set(AVAILABILITY_POLLER.PROVIDER, provider)
            .set(AVAILABILITY_POLLER.PARENT_REF, parentRef)
            .set(AVAILABILITY_POLLER.POI_ID, poiId)
            .set(AVAILABILITY_POLLER.ACTIVE, true)
            .set(AVAILABILITY_POLLER.NEXT_RUN_AT, insertNextRun)
            .onConflict(AVAILABILITY_POLLER.PROVIDER, AVAILABILITY_POLLER.PARENT_REF)
            .doUpdate()
            .set(AVAILABILITY_POLLER.ACTIVE, true) // revive dormant
            .set(AVAILABILITY_POLLER.POI_ID, poiId) // refresh representative
            // Pull next_run_at earlier only when asked; never push it later.
            .set(
                AVAILABILITY_POLLER.NEXT_RUN_AT,
                if (pullNextRunAt != null) {
                    DSL.least(AVAILABILITY_POLLER.NEXT_RUN_AT, DSL.`val`(pullNextRunAt))
                } else {
                    AVAILABILITY_POLLER.NEXT_RUN_AT
                },
            ).set(AVAILABILITY_POLLER.UPDATED_AT, now)
            .returningResult(AVAILABILITY_POLLER.ID)
            .fetchOne()!!
            .value1()!!
    }

    fun findById(id: Long): Poller? =
        ctx
            .selectFrom(AVAILABILITY_POLLER)
            .where(AVAILABILITY_POLLER.ID.eq(id))
            .fetchOne()
            ?.let(::fromRecord)

    /** A poller plus its current attached-watch count, for the dashboard list. */
    data class PollerListItem(
        val poller: Poller,
        val attachedWatches: Int,
    )

    /**
     * Filtered list of pollers newest-first by created_at, each carrying its
     * attached-watch count (a correlated COUNT over `availability_watch_poller`).
     * Used by the /availability dashboard's Pollers tab. [active] filters to
     * active (true) or dormant (false) pollers; null returns both.
     */
    fun list(
        active: Boolean? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
        offset: Int = 0,
    ): List<PollerListItem> {
        val effectiveLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val attachedWatches =
            DSL
                .selectCount()
                .from(AVAILABILITY_WATCH_POLLER)
                .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(AVAILABILITY_POLLER.ID))
                .asField<Int>("attached_watches")
        return ctx
            .select(AVAILABILITY_POLLER.asterisk(), attachedWatches)
            .from(AVAILABILITY_POLLER)
            .where(if (active == null) DSL.noCondition() else AVAILABILITY_POLLER.ACTIVE.eq(active))
            .orderBy(AVAILABILITY_POLLER.CREATED_AT.desc(), AVAILABILITY_POLLER.ID.desc())
            .limit(effectiveLimit)
            .offset(offset.coerceAtLeast(0))
            .fetch { PollerListItem(fromRecord(it), it.get(attachedWatches)) }
    }

    fun count(active: Boolean? = null): Int =
        ctx
            .selectCount()
            .from(AVAILABILITY_POLLER)
            .where(if (active == null) DSL.noCondition() else AVAILABILITY_POLLER.ACTIVE.eq(active))
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * Header counters for the dashboard. `active`/`dormant` split on the
     * `active` flag; `dueNow` = active && next_run_at <= now && not currently
     * leased; `claimed` = a live lease (claimed_until in the future). One DB
     * round-trip via conditional aggregates.
     */
    data class Summary(
        val active: Int,
        val dormant: Int,
        val dueNow: Int,
        val claimed: Int,
    )

    fun summary(now: OffsetDateTime): Summary {
        val record =
            ctx
                .select(
                    DSL.count(DSL.case_().`when`(AVAILABILITY_POLLER.ACTIVE.isTrue, 1)).`as`("active"),
                    DSL.count(DSL.case_().`when`(AVAILABILITY_POLLER.ACTIVE.isFalse, 1)).`as`("dormant"),
                    DSL
                        .count(
                            DSL
                                .case_()
                                .`when`(
                                    AVAILABILITY_POLLER.ACTIVE.isTrue
                                        .and(AVAILABILITY_POLLER.NEXT_RUN_AT.le(now))
                                        .and(
                                            AVAILABILITY_POLLER.CLAIMED_UNTIL.isNull
                                                .or(AVAILABILITY_POLLER.CLAIMED_UNTIL.lt(now)),
                                        ),
                                    1,
                                ),
                        ).`as`("due_now"),
                    DSL
                        .count(
                            DSL
                                .case_()
                                .`when`(
                                    AVAILABILITY_POLLER.CLAIMED_UNTIL.isNotNull
                                        .and(AVAILABILITY_POLLER.CLAIMED_UNTIL.ge(now)),
                                    1,
                                ),
                        ).`as`("claimed"),
                ).from(AVAILABILITY_POLLER)
                .fetchOne()!!
        return Summary(
            active = record.get("active", Int::class.java),
            dormant = record.get("dormant", Int::class.java),
            dueNow = record.get("due_now", Int::class.java),
            claimed = record.get("claimed", Int::class.java),
        )
    }

    /** All poller ids currently linked to [watchId]. */
    fun pollerIdsForWatch(watchId: Long): List<Long> =
        ctx
            .select(AVAILABILITY_WATCH_POLLER.POLLER_ID)
            .from(AVAILABILITY_WATCH_POLLER)
            .where(AVAILABILITY_WATCH_POLLER.WATCH_ID.eq(watchId))
            .fetch(AVAILABILITY_WATCH_POLLER.POLLER_ID)
            .filterNotNull()

    /** All watch ids currently linked to [pollerId], regardless of watch status. */
    fun watchIdsForPoller(pollerId: Long): List<Long> =
        ctx
            .select(AVAILABILITY_WATCH_POLLER.WATCH_ID)
            .from(AVAILABILITY_WATCH_POLLER)
            .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(pollerId))
            .fetch(AVAILABILITY_WATCH_POLLER.WATCH_ID)
            .filterNotNull()

    /**
     * Reconciles the watch->poller links for [watchId] to exactly
     * [pollerIds]: links new ones, drops links to pollers no longer in the
     * set. Called when a watch's derived poll targets change (e.g. a
     * date-window edit changes which vendor call units cover it).
     */
    fun replaceLinksForWatch(
        watchId: Long,
        pollerIds: Set<Long>,
    ) {
        val existing = pollerIdsForWatch(watchId).toSet()
        (pollerIds - existing).forEach { linkWatch(watchId, it) }
        val stale = existing - pollerIds
        if (stale.isNotEmpty()) {
            ctx
                .deleteFrom(AVAILABILITY_WATCH_POLLER)
                .where(AVAILABILITY_WATCH_POLLER.WATCH_ID.eq(watchId))
                .and(AVAILABILITY_WATCH_POLLER.POLLER_ID.`in`(stale))
                .execute()
        }
    }

    fun linkWatch(
        watchId: Long,
        pollerId: Long,
    ) {
        ctx
            .insertInto(AVAILABILITY_WATCH_POLLER)
            .set(AVAILABILITY_WATCH_POLLER.WATCH_ID, watchId)
            .set(AVAILABILITY_WATCH_POLLER.POLLER_ID, pollerId)
            .onConflictDoNothing()
            .execute()
    }

    /**
     * Deactivates any active poller with zero remaining watch links — the
     * cleanup step after a watch is unlinked (retired, deleted, or moved to
     * a different poller) leaves its old poller dormant rather than
     * deleting it, so a future watch on the same (provider, parent_ref)
     * revives it via [upsertActive] instead of re-inserting.
     */
    fun deactivatePollersWithNoLinks(): Int =
        ctx
            .update(AVAILABILITY_POLLER)
            .set(AVAILABILITY_POLLER.ACTIVE, false)
            .set(AVAILABILITY_POLLER.UPDATED_AT, OffsetDateTime.now())
            .where(AVAILABILITY_POLLER.ACTIVE.isTrue)
            .andNotExists(
                ctx
                    .selectOne()
                    .from(AVAILABILITY_WATCH_POLLER)
                    .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(AVAILABILITY_POLLER.ID)),
            ).execute()

    /**
     * Active watches linked to [pollerId] whose date window still reaches
     * the future. `end_date >= today (UTC)` is a cheap prefilter only — the
     * executor derives the exact target-local clamp per run; a watch that
     * passes here but has already elapsed in target-local time is a no-op
     * for that run, not a correctness bug.
     *
     * Delegates the watch+reservable row mapping to [AvailabilityWatchRepo]
     * rather than re-deriving it, so the two repos can't drift on shape.
     */
    fun liveWatchesForPoller(pollerId: Long): List<AvailabilityWatchRepo.Watch> =
        ctx
            .select(watchRepo.baseSelectFields())
            .from(AVAILABILITY_WATCH)
            .join(AVAILABILITY_WATCH_POLLER)
            .on(AVAILABILITY_WATCH_POLLER.WATCH_ID.eq(AVAILABILITY_WATCH.ID))
            .leftJoin(RESERVABLES)
            .on(RESERVABLES.ID.eq(AVAILABILITY_WATCH.RESERVABLE_ID))
            .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(pollerId))
            .and(AVAILABILITY_WATCH.STATUS.eq(WatchStatus.ACTIVE.wireValue))
            .and(AVAILABILITY_WATCH.END_DATE.ge(LocalDate.now(ZoneOffset.UTC)))
            .fetch { watchRepo.fromRecord(it) }

    /**
     * Retires a poller: marks [elapsedWatchIds] `done` (their windows have
     * fully elapsed), drops every watch->poller link for [pollerId], and
     * deactivates the poller. Runs in one transaction so a crash mid-retire
     * can't leave a `done` watch still linked, or an active link to a
     * dormant poller.
     */
    fun retire(
        pollerId: Long,
        elapsedWatchIds: List<Long>,
    ) {
        ctx.transaction { config ->
            val txn = DSL.using(config)
            if (elapsedWatchIds.isNotEmpty()) {
                txn
                    .update(AVAILABILITY_WATCH)
                    .set(AVAILABILITY_WATCH.STATUS, WatchStatus.DONE.wireValue)
                    .set(AVAILABILITY_WATCH.UPDATED_AT, OffsetDateTime.now())
                    .where(AVAILABILITY_WATCH.ID.`in`(elapsedWatchIds))
                    .execute()
            }
            txn
                .deleteFrom(AVAILABILITY_WATCH_POLLER)
                .where(AVAILABILITY_WATCH_POLLER.POLLER_ID.eq(pollerId))
                .execute()
            txn
                .update(AVAILABILITY_POLLER)
                .set(AVAILABILITY_POLLER.ACTIVE, false)
                .set(AVAILABILITY_POLLER.UPDATED_AT, OffsetDateTime.now())
                .where(AVAILABILITY_POLLER.ID.eq(pollerId))
                .execute()
        }
    }

    /**
     * Claim up to [limit] active pollers whose next_run_at has passed.
     * Lease extends `claimed_until` by [leaseDuration]; expired or null
     * leases are eligible. Returns the rows the caller now owns.
     *
     * Postgres `FOR UPDATE SKIP LOCKED` means parallel scheduler ticks (or
     * a future second worker) won't hand the same row to two callers.
     */
    override fun claimDue(
        now: OffsetDateTime,
        limit: Int,
        leaseDuration: Duration,
    ): List<Poller> {
        val token = UUID.randomUUID().toString()
        val leaseUntil = now.plus(leaseDuration)
        // Two-step claim: SELECT … FOR UPDATE SKIP LOCKED, then UPDATE the
        // selected ids. Done in a single transaction.
        return ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val due =
                txn
                    .select(AVAILABILITY_POLLER.ID)
                    .from(AVAILABILITY_POLLER)
                    .where(AVAILABILITY_POLLER.ACTIVE.isTrue)
                    .and(AVAILABILITY_POLLER.NEXT_RUN_AT.le(now))
                    .and(
                        AVAILABILITY_POLLER.CLAIMED_UNTIL.isNull
                            .or(AVAILABILITY_POLLER.CLAIMED_UNTIL.lt(now)),
                    ).orderBy(AVAILABILITY_POLLER.NEXT_RUN_AT.asc())
                    .limit(limit)
                    .forUpdate()
                    .skipLocked()
                    .fetch(AVAILABILITY_POLLER.ID)
            if (due.isEmpty()) return@transactionResult emptyList()
            txn
                .update(AVAILABILITY_POLLER)
                .set(AVAILABILITY_POLLER.CLAIM_TOKEN, token)
                .set(AVAILABILITY_POLLER.CLAIMED_UNTIL, leaseUntil)
                .set(AVAILABILITY_POLLER.UPDATED_AT, now)
                .where(AVAILABILITY_POLLER.ID.`in`(due))
                .execute()
            txn
                .selectFrom(AVAILABILITY_POLLER)
                .where(AVAILABILITY_POLLER.ID.`in`(due))
                .fetch { fromRecord(it) }
        }
    }

    /**
     * Release a claimed poller after the worker finishes. Verifies the
     * claim_token matches; mismatched calls (lease expired, reclaimed)
     * return false without modifying the row.
     */
    override fun release(
        id: Long,
        token: String,
        nextRunAt: OffsetDateTime,
        ranAt: OffsetDateTime,
    ): Boolean =
        ctx
            .update(AVAILABILITY_POLLER)
            .set(AVAILABILITY_POLLER.CLAIM_TOKEN, null as String?)
            .set(AVAILABILITY_POLLER.CLAIMED_UNTIL, null as OffsetDateTime?)
            .set(AVAILABILITY_POLLER.NEXT_RUN_AT, nextRunAt)
            .set(AVAILABILITY_POLLER.LAST_RUN_AT, ranAt)
            .set(AVAILABILITY_POLLER.UPDATED_AT, ranAt)
            .where(AVAILABILITY_POLLER.ID.eq(id))
            .and(AVAILABILITY_POLLER.CLAIM_TOKEN.eq(token))
            .execute() > 0

    /**
     * Boot recovery: rows whose lease expired without being released
     * (worker crashed, app restarted) get their claim wiped so the next
     * tick can re-claim them.
     */
    override fun reclaimExpired(now: OffsetDateTime): Int =
        ctx
            .update(AVAILABILITY_POLLER)
            .set(AVAILABILITY_POLLER.CLAIM_TOKEN, null as String?)
            .set(AVAILABILITY_POLLER.CLAIMED_UNTIL, null as OffsetDateTime?)
            .set(AVAILABILITY_POLLER.UPDATED_AT, now)
            .where(AVAILABILITY_POLLER.CLAIMED_UNTIL.isNotNull)
            .and(AVAILABILITY_POLLER.CLAIMED_UNTIL.lt(now))
            .execute()

    private fun fromRecord(r: Record): Poller =
        Poller(
            id = r.get(AVAILABILITY_POLLER.ID)!!,
            provider = r.get(AVAILABILITY_POLLER.PROVIDER)!!,
            parentRef = r.get(AVAILABILITY_POLLER.PARENT_REF)!!,
            poiId = r.get(AVAILABILITY_POLLER.POI_ID)!!,
            active = r.get(AVAILABILITY_POLLER.ACTIVE)!!,
            nextRunAt = r.get(AVAILABILITY_POLLER.NEXT_RUN_AT)!!,
            claimedUntil = r.get(AVAILABILITY_POLLER.CLAIMED_UNTIL),
            claimToken = r.get(AVAILABILITY_POLLER.CLAIM_TOKEN),
            lastRunAt = r.get(AVAILABILITY_POLLER.LAST_RUN_AT),
            createdAt = r.get(AVAILABILITY_POLLER.CREATED_AT)!!,
            updatedAt = r.get(AVAILABILITY_POLLER.UPDATED_AT)!!,
        )
}
