package ca.floo.roadtrip.di

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler

internal class SlackInteractivityWatchesPort(
    private val watchService: AvailabilityWatchService,
    private val alertDispatcher: WatchAlertDispatcher,
) : SlackInteractivityHandler.Watches {
    override fun setStatus(
        id: Long,
        status: WatchStatus,
    ): AvailabilityWatchRepo.Watch? = watchService.update(id = id, status = status)

    override fun snapshotAndDelete(id: Long): AvailabilityWatchRepo.Watch? = watchService.deleteReturningSnapshot(id)

    override fun buildStatusNotice(
        watch: AvailabilityWatchRepo.Watch,
        state: WatchStatusNotice.State,
    ): WatchStatusNotice = alertDispatcher.statusNoticeForWatch(watch, state)
}
