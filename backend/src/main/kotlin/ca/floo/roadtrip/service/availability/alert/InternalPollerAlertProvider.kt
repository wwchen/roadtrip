package ca.floo.roadtrip.service.availability.alert

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import java.time.OffsetDateTime

/**
 * Default [AlertProvider]. Detects openings by polling upstream vendors on a
 * coalesced schedule: each watch is linked to the poller row(s) covering its
 * resolved (provider, parent_ref) set, and the executor drives those pollers.
 *
 * Wraps today's [AvailabilityPollerMembership] sync + orphan-reap so watch
 * writes and this provider's hooks stay in the same transaction: both hooks
 * work through the transaction-bound repo the [WatchAlertScope] hands them.
 */
internal class InternalPollerAlertProvider(
    private val membership: AvailabilityPollerMembership,
) : AlertProvider {
    override val id: String = AlertProviderRegistry.INTERNAL_POLLER_ID

    /** Platform polls upstream; no vendor-hosted webhook route. */
    override val hostsAlerts: Boolean = false

    override fun onWatchActivated(
        scope: WatchAlertScope,
        watch: AvailabilityWatchRepo.Watch,
    ) {
        // A create (or a resume) should pull the coalesced poller's next_run_at
        // earlier so the newly-linked watch doesn't wait a full cadence. sync()
        // forwards this only as an earlier-pull; it never pushes next_run_at
        // later. Passing the current time matches the pre-seam behavior.
        membership.sync(
            watch,
            scope.pollerRepo,
            tighterCadencePull = OffsetDateTime.now(),
        )
    }

    override fun onWatchDeactivated(
        scope: WatchAlertScope,
        watch: AvailabilityWatchRepo.Watch,
    ) {
        val pollerRepo = scope.pollerRepo
        // Pause/done: clears the watch's remaining links. Delete: the FK
        // cascade has already cleared them, so this is a safe no-op. Then reap
        // any poller that lost its last link.
        pollerRepo.replaceLinksForWatch(watch.id, emptySet())
        pollerRepo.deactivatePollersWithNoLinks()
    }
}
