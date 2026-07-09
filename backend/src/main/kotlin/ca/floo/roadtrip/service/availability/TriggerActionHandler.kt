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

/**
 * Dispatch table matching a watch's `trigger_kinds` to their [TriggerActionHandler]s.
 * A kind with no registered handler resolves to `null` (inert — the current
 * `atc` behavior). Composed once at runtime startup.
 */
internal class TriggerActionRegistry(handlers: List<TriggerActionHandler>) {
    private val byKind: Map<String, TriggerActionHandler> = handlers.associateBy { it.kind }

    init {
        require(handlers.size == byKind.size) {
            "duplicate handler kinds in TriggerActionRegistry: " +
                handlers.groupBy { it.kind }.filterValues { it.size > 1 }.keys
        }
    }

    /** `null` == inert (current `atc` behavior for unknown/absent handlers). */
    fun forKind(kind: String): TriggerActionHandler? = byKind[kind]
}
