package ca.floo.roadtrip.service.availability.alert

import ca.floo.roadtrip.repo.AvailabilityWatchRepo

/**
 * Chooses the [AlertProvider] responsible for detecting openings on a given
 * watch. v1 ships one provider ([InternalPollerAlertProvider]) and always
 * dispatches to it; a later revision will pick per-watch based on the watch's
 * target vendors + `preferred_availability_source` + each adapter's
 * capability, and that dispatch rule moves into [forWatch] rather than into
 * [ca.floo.roadtrip.service.availability.AvailabilityWatchService].
 */
internal class AlertProviderRegistry(
    private val providers: List<AlertProvider>,
) {
    init {
        require(providers.isNotEmpty()) { "AlertProviderRegistry needs at least one AlertProvider" }
    }

    /**
     * v1: always the internal poller. When alert-provider selection becomes
     * per-watch, the dispatch rule (target vendors + preferred source +
     * adapter capability) lives here — callers never need to change.
     */
    fun forWatch(watch: AvailabilityWatchRepo.Watch): AlertProvider = providers.first { it.id == INTERNAL_POLLER_ID }

    companion object {
        const val INTERNAL_POLLER_ID = "internal_poller"
    }
}
