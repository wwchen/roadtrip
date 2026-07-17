package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Mutates watches and hands the "who detects openings" work to the
 * [AlertProviderRegistry]. Single seam for routes; routes never touch
 * [AvailabilityWatchRepo] or [ca.floo.roadtrip.repo.AvailabilityPollerRepo]
 * for writes.
 *
 * A watch is user intent; the alert provider is what actually detects
 * openings for it — today always the internal poller (watches are linked to
 * coalesced per-(provider, parent_ref) poller rows), tomorrow potentially a
 * vendor-hosted alert API. Every watch mutation transacts across
 * `availability_watch` and whatever state the chosen alert provider owns, so
 * a watch is never visible without its alert-provider bookkeeping resolved.
 *
 * Internal because it composes [AlertProviderRegistry], which is module-
 * internal; everything is one Gradle module (routes included), so `internal`
 * costs nothing and keeps upstream-vendor shape from leaking through a public
 * API.
 */
internal class AvailabilityWatchService(
    private val ctx: DSLContext,
    private val alertProviders: AlertProviderRegistry,
    private val capabilityValidator: WatchCapabilityValidator = NoopWatchCapabilityValidator,
) {
    fun create(input: AvailabilityWatchRepo.CreateInput): Watch =
        ctx.transactionResult { config ->
            WatchTriggerConfig.validateCreate(input)
            val txn = DSL.using(config)
            val watch = AvailabilityWatchRepo(txn).create(input)
            capabilityValidator.validate(watch)
            alertProviders.forWatch(watch).onWatchActivated(txn, watch)
            watch
        }

    fun update(
        id: Long,
        input: AvailabilityWatchRepo.UpdateInput,
    ): Watch? =
        ctx.transactionResult { config ->
            WatchTriggerConfig.validateUpdate(input)
            val txn = DSL.using(config)
            val updated = AvailabilityWatchRepo(txn).update(id, input) ?: return@transactionResult null
            capabilityValidator.validate(updated)
            // ACTIVE → the alert provider (re)subscribes / re-syncs poller links;
            // any non-ACTIVE status is a deactivate as far as opening-detection
            // is concerned — the watch holds no live subscription.
            val provider = alertProviders.forWatch(updated)
            if (updated.status == WatchStatus.ACTIVE) {
                provider.onWatchActivated(txn, updated)
            } else {
                provider.onWatchDeactivated(txn, updated)
            }
            updated
        }

    fun delete(id: Long): Boolean =
        ctx.transactionResult { config ->
            val txn = DSL.using(config)
            val repo = AvailabilityWatchRepo(txn)
            // Snapshot pre-delete so the alert provider's deactivate hook has a
            // Watch to work with — the row itself is about to disappear (FK
            // cascade will drop its availability_watch_poller links).
            val snapshot = repo.findById(id) ?: return@transactionResult false
            val deleted = repo.delete(id)
            if (deleted) {
                alertProviders.forWatch(snapshot).onWatchDeactivated(txn, snapshot)
            }
            deleted
        }
}
