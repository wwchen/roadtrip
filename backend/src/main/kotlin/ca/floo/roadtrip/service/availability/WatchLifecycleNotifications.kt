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

internal object NoopWatchLifecycleNotifications : WatchLifecycleNotifications {
    override fun afterCreate(watch: AvailabilityWatchRepo.Watch) = Unit

    override fun afterUpdate(
        before: AvailabilityWatchRepo.Watch,
        after: AvailabilityWatchRepo.Watch,
    ) = Unit

    override fun afterDelete(watch: AvailabilityWatchRepo.Watch) = Unit
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
        if (WatchInitialNotificationPolicy.shouldDispatchAfterUpdate(before, after)) {
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
}
