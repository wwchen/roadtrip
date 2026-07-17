package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo

/**
 * Decides whether an update changed watch meaning enough to send the current
 * window state again. The watch service owns lifecycle mutation side effects;
 * this policy owns the business rule so ingress handlers do not grow domain
 * logic.
 */
internal object WatchInitialNotificationPolicy {
    fun shouldDispatchAfterUpdate(
        before: AvailabilityWatchRepo.Watch,
        after: AvailabilityWatchRepo.Watch,
    ): Boolean {
        if (before.status != after.status) return true
        if (after.status != WatchStatus.ACTIVE) return false
        if (before.status != WatchStatus.ACTIVE) return true
        if (before.startDate != after.startDate || before.endDate != after.endDate) return true
        if (before.targets.toSet() != after.targets.toSet()) return true
        if (before.campsiteFilters != after.campsiteFilters) return true
        return (after.triggerKinds.toSet() - before.triggerKinds.toSet()).isNotEmpty()
    }
}
