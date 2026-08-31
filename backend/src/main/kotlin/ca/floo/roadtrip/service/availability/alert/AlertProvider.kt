package ca.floo.roadtrip.service.availability.alert

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.support.Dispatchable

/**
 * Who detects openings for a watch. The internal poller is the default; a
 * vendor-hosted implementation (e.g. Campflare's alert API) subscribes
 * upstream in [onWatchActivated], receives webhooks on a route it owns,
 * normalizes payloads to [ca.floo.roadtrip.model.availability.CellTransition],
 * and feeds the same [ca.floo.roadtrip.service.availability.WatchAlertDispatcher].
 * A new alert provider is one file under `alert/providers/<vendor>/` plus one
 * registry row.
 *
 * The hooks run inside [ca.floo.roadtrip.service.availability.AvailabilityWatchService]'s
 * watch-write transaction; [WatchAlertScope] hands them repo handles already bound
 * to it, so a hosted implementation never sees a database type it has no use for.
 */
internal interface AlertProvider : Dispatchable<AlertProviderId> {
    /** Stable slug identifying this provider ("internal_poller", later "campflare"). */
    val id: String

    /**
     * `false` = platform polls this vendor for openings (internal poller);
     * `true` = vendor pushes alerts and this provider owns the webhook route.
     */
    val hostsAlerts: Boolean

    override fun canHandle(key: AlertProviderId): Boolean = key.slug == id

    /**
     * Called after a watch is created or transitions into [ca.floo.roadtrip.service.availability.WatchStatus.ACTIVE].
     * Internal poller: reconciles the watch's poller links to its resolved
     * (provider, parent_ref) set and pulls the coalesced poller's next run
     * earlier when this watch's cadence is tighter. Vendor-hosted: registers
     * an upstream alert subscription for the watch's targets.
     */
    fun onWatchActivated(
        scope: WatchAlertScope,
        watch: AvailabilityWatchRepo.Watch,
    )

    /**
     * Called after a watch is paused, marked done, or deleted. Internal
     * poller: drops the watch's poller links (delete: the FK cascade has
     * already dropped them; pause/done: this call drops them) and deactivates
     * any now-orphaned poller. Vendor-hosted: cancels the upstream
     * subscription.
     */
    fun onWatchDeactivated(
        scope: WatchAlertScope,
        watch: AvailabilityWatchRepo.Watch,
    )
}
