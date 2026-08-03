package ca.floo.roadtrip.di

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler

// Pause/resume/delete here apply with NO per-click owner check on purpose: the
// isolation comes from owner-scoped card DELIVERY (see
// [ca.floo.roadtrip.service.availability.WatchNotificationTargetResolver]). A
// watch's card only reaches a channel its owner controls, never the shared
// default, so a different user never sees the buttons to click. Do NOT
// reintroduce a shared-channel send for owned watches without adding an owner
// check here.
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
