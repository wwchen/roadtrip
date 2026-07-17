package ca.floo.roadtrip.service.availability.alert

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import org.jooq.DSLContext

internal const val INTERNAL_POLLER_ALERT_PROVIDER_ID = "internal_poller"

/**
 * Who detects openings for a watch. The internal poller is the default; a
 * vendor-hosted implementation (e.g. Campflare's alert API) subscribes
 * upstream in [onWatchActivated], receives webhooks on a route it owns,
 * normalizes payloads to [ca.floo.roadtrip.models.availability.CellTransition],
 * and feeds the same [ca.floo.roadtrip.service.availability.WatchAlertDispatcher].
 * A new alert provider is one file under `alert/providers/<vendor>/` plus one
 * Koin binding.
 *
 * The hooks run inside [ca.floo.roadtrip.service.availability.AvailabilityWatchService]'s
 * watch-write transaction; membership writes are transactional today, so the
 * [DSLContext] passed in is the txn context — never open a new connection.
 */
internal interface AlertProvider {
    /** Stable slug identifying this provider ("internal_poller", later "campflare"). */
    val id: String

    /**
     * `false` = platform polls this vendor for openings (internal poller);
     * `true` = vendor pushes alerts and this provider owns the webhook route.
     */
    val hostsAlerts: Boolean

    /**
     * Called after a watch is created or transitions into [ca.floo.roadtrip.service.availability.WatchStatus.ACTIVE].
     * Internal poller: reconciles the watch's poller links to its resolved
     * (provider, parent_ref) set and pulls the coalesced poller's next run
     * earlier when this watch's cadence is tighter. Vendor-hosted: registers
     * an upstream alert subscription for the watch's targets.
     */
    fun onWatchActivated(
        txn: DSLContext,
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
        txn: DSLContext,
        watch: AvailabilityWatchRepo.Watch,
    )
}
