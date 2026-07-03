package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.repo.ReservableRepo
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime

/**
 * Mutates watches and keeps their poller membership in sync. Single seam
 * for routes; routes never touch [AvailabilityWatchRepo] or
 * [AvailabilityPollerRepo] for writes.
 *
 * A watch is user intent; a poller is the physical, coalesced
 * per-(provider, parent_ref) unit of scheduled work. Every watch mutation
 * transacts across `availability_watch` and its `availability_watch_poller`
 * links so a watch is never visible without its poller membership resolved.
 *
 * Internal because it composes [AvailabilityPollerMembership] and
 * [AvailabilityTargetResolver], both module-internal; everything is one
 * Gradle module (routes included), so `internal` costs nothing and keeps
 * upstream-vendor shape from leaking through a public API.
 */
internal class AvailabilityWatchService(
    private val ctx: DSLContext,
    private val reservablesRepo: ReservableRepo,
    private val targets: AvailabilityTargetResolver,
) {
    private fun membershipFor(txn: DSLContext): AvailabilityPollerMembership =
        AvailabilityPollerMembership(WatchScopeResolver(reservablesRepo), targets)

    fun create(input: AvailabilityWatchRepo.CreateInput): Watch =
        ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val watch = AvailabilityWatchRepo(txn).create(input)
            membershipFor(txn).sync(watch, AvailabilityPollerRepo(txn), tighterCadencePull = OffsetDateTime.now())
            watch
        }

    fun update(
        id: Long,
        input: AvailabilityWatchRepo.UpdateInput,
    ): Watch? =
        ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val updated = AvailabilityWatchRepo(txn).update(id, input) ?: return@transactionResult null
            // A cadence tighten (or a resume) should pull next_run_at earlier; simplest
            // correct behavior is to allow a pull to now whenever the watch is active.
            // Membership.sync forwards this only as an earlier-pull; it never pushes
            // next_run_at later. A non-active watch drops its links and pulls nothing.
            val pull = if (updated.status == WatchStatus.ACTIVE) OffsetDateTime.now() else null
            membershipFor(txn).sync(updated, AvailabilityPollerRepo(txn), tighterCadencePull = pull)
            updated
        }

    fun delete(id: Long): Boolean =
        ctx.transactionResult { config ->
            val txn = DSL.using(config)
            // FK cascade drops the watch's availability_watch_poller links.
            val deleted = AvailabilityWatchRepo(txn).delete(id)
            AvailabilityPollerRepo(txn).deactivatePollersWithNoLinks()
            deleted
        }
}
