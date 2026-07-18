package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Service-layer side effects for watch lifecycle mutations. */
internal interface WatchLifecycleNotifications {
    fun afterCreate(watch: AvailabilityWatchRepo.Watch)

    fun afterUpdate(
        before: AvailabilityWatchRepo.Watch,
        after: AvailabilityWatchRepo.Watch,
    )

    fun afterDelete(watch: AvailabilityWatchRepo.Watch)
}

internal class DispatchingWatchLifecycleNotifications(
    private val dispatcher: WatchAlertDispatcher,
    private val scope: CoroutineScope,
) : WatchLifecycleNotifications {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterCreate(watch: AvailabilityWatchRepo.Watch) {
        scheduleInitial(watch)
    }

    override fun afterUpdate(
        before: AvailabilityWatchRepo.Watch,
        after: AvailabilityWatchRepo.Watch,
    ) {
        if (shouldDispatchAfterUpdate(before, after)) {
            scheduleInitial(after)
        }
    }

    override fun afterDelete(watch: AvailabilityWatchRepo.Watch) {
        scope.launch {
            runCatching { dispatcher.dispatchStopped(watch) }
                .onFailure { log.warn("stopped notify for watch {} failed", watch.id, it) }
        }
    }

    private fun scheduleInitial(watch: AvailabilityWatchRepo.Watch) {
        scope.launch {
            runCatching { dispatcher.dispatchInitial(watch) }
                .onFailure { log.warn("initial notify for watch {} failed", watch.id, it) }
        }
    }

    private fun shouldDispatchAfterUpdate(
        before: AvailabilityWatchRepo.Watch,
        after: AvailabilityWatchRepo.Watch,
    ): Boolean {
        if (before.status != after.status) return true
        if (after.status != WatchStatus.ACTIVE) return false
        if (before.startDate != after.startDate || before.endDate != after.endDate) return true
        if (before.targets.toSet() != after.targets.toSet()) return true
        if (before.campsiteFilters != after.campsiteFilters) return true
        return (after.triggerKinds.toSet() - before.triggerKinds.toSet()).isNotEmpty()
    }
}
