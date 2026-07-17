package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.Dispatchable

/**
 * Fires one or more trigger-action kinds (notification, ATC route, …)
 * for a watch that has just detected an opening. Handlers are matched by
 * kind slug against `availability_watches.trigger_kinds`; an unknown or
 * unregistered kind is inert (no handler ⇒ no fire, no error). Registering a
 * new kind is one file under
 * `service/availability/` plus one entry in the runtime's
 * [TriggerActionRegistry] list.
 */
internal interface TriggerActionHandler : Dispatchable<TriggerKind> {
    /** Stable slugs matching `availability_watches.trigger_kinds`. */
    val kinds: Set<String>

    override fun canHandle(key: TriggerKind): Boolean = key.slug in kinds

    /**
     * Fires the side effect for [watch] over its [openings]. Returns `true`
     * iff delivery succeeded — drives the `stopWhenTriggered` DONE transition
     * in [WatchAlertDispatcher], so a delivery failure never silences a watch
     * we could not notify on. Never throws: implementations surface transport
     * failures as `false`.
     */
    suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<TriggerOpening>,
    ): Boolean
}
