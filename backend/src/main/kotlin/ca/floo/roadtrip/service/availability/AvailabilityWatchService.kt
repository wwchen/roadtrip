package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo.Watch
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import kotlinx.serialization.json.JsonObject
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate

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
    private val capabilityValidator: WatchCapabilityValidator,
    private val lifecycleNotifications: WatchLifecycleNotifications,
) {
    fun create(
        ownerUserId: UserId,
        targets: List<AvailabilityWatchTargetRepo.TargetInput>,
        campsiteFilters: JsonObject,
        startDate: LocalDate,
        endDate: LocalDate,
        cadenceSec: Int?,
        triggerKinds: List<String>,
        triggerConfig: JsonObject,
        stopWhenTriggered: Boolean,
    ): Watch {
        val watch =
            ctx.transactionResult { config ->
                val input =
                    AvailabilityWatchRepo.CreateInput(
                        ownerUserId = ownerUserId.value,
                        targets = targets,
                        campsiteFilters = campsiteFilters,
                        startDate = startDate,
                        endDate = endDate,
                        cadenceSec = cadenceSec,
                        triggerKinds = triggerKinds,
                        triggerConfig = triggerConfig,
                        stopWhenTriggered = stopWhenTriggered,
                    )
                WatchTriggerConfig.validateCreate(input)
                val txn = DSL.using(config)
                val created = AvailabilityWatchRepo(txn).create(input)
                capabilityValidator.validate(created)
                alertProviders.forWatch(created).onWatchActivated(txn, created)
                created
            }
        lifecycleNotifications.afterCreate(watch)
        return watch
    }

    fun update(
        id: Long,
        targets: List<AvailabilityWatchTargetRepo.TargetInput>? = null,
        campsiteFilters: JsonObject? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        cadenceSec: Int? = null,
        triggerKinds: List<String>? = null,
        triggerConfig: JsonObject? = null,
        stopWhenTriggered: Boolean? = null,
        status: WatchStatus? = null,
    ): Watch? {
        val update =
            ctx.transactionResult { config ->
                val input =
                    AvailabilityWatchRepo.UpdateInput(
                        targets = targets,
                        campsiteFilters = campsiteFilters,
                        startDate = startDate,
                        endDate = endDate,
                        cadenceSec = cadenceSec,
                        triggerKinds = triggerKinds,
                        triggerConfig = triggerConfig,
                        stopWhenTriggered = stopWhenTriggered,
                        status = status,
                    )
                WatchTriggerConfig.validateUpdate(input)
                val triggerIntentTouched = input.triggerKinds != null || input.triggerConfig != null
                val txn = DSL.using(config)
                val repo = AvailabilityWatchRepo(txn)
                val before = repo.findById(id) ?: return@transactionResult null
                val updated = repo.update(id, input) ?: return@transactionResult null
                if (triggerIntentTouched) WatchTriggerConfig.validateSnapshot(updated)
                capabilityValidator.validate(updated)
                // ACTIVE -> the alert provider (re)subscribes / re-syncs poller links;
                // any non-ACTIVE status is a deactivate as far as opening-detection
                // is concerned -- the watch holds no live subscription.
                val provider = alertProviders.forWatch(updated)
                if (updated.status == WatchStatus.ACTIVE) {
                    provider.onWatchActivated(txn, updated)
                } else {
                    provider.onWatchDeactivated(txn, updated)
                }
                before to updated
            }
        update?.let { (before, after) -> lifecycleNotifications.afterUpdate(before, after) }
        return update?.second
    }

    fun delete(id: Long): Boolean = deleteReturningSnapshot(id) != null

    fun deleteReturningSnapshot(id: Long): Watch? {
        val snapshot =
            ctx.transactionResult { config ->
                val txn = DSL.using(config)
                val repo = AvailabilityWatchRepo(txn)
                // Snapshot pre-delete so the alert provider's deactivate hook has a
                // Watch to work with -- the row itself is about to disappear (FK
                // cascade will drop its availability_watch_poller links).
                val existing = repo.findById(id) ?: return@transactionResult null
                val deleted = repo.delete(id)
                if (deleted) {
                    alertProviders.forWatch(existing).onWatchDeactivated(txn, existing)
                    existing
                } else {
                    null
                }
            }
        snapshot?.let(lifecycleNotifications::afterDelete)
        return snapshot
    }
}
