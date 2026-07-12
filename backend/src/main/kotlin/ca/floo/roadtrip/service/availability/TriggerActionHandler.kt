package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.notification.WatchOpening

/**
 * Fires one trigger-action kind (Slack message, ATC route, future email, …)
 * for a watch that has just detected an opening. Handlers are matched by
 * kind slug against `availability_watches.trigger_kinds`; an unknown or
 * unregistered kind is inert (no handler ⇒ no fire, no error) — the current
 * `atc` behavior. Registering a new kind is one file under
 * `service/availability/` plus one entry in the runtime's
 * [TriggerActionRegistry] list.
 */
internal interface TriggerActionHandler {
    /** Stable slug matching `availability_watches.trigger_kinds`. */
    val kind: String

    /**
     * Fires the side effect for [watch] over its [openings]. Returns `true`
     * iff delivery succeeded — drives the `stopWhenTriggered` DONE transition
     * in [WatchAlertDispatcher], so a delivery failure never silences a watch
     * we could not notify on. Never throws: implementations surface transport
     * failures as `false`.
     */
    suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<WatchOpening>,
    ): Boolean
}
