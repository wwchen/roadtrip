package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.notification.SlackNotificationService

/**
 * The `slack_notify` [TriggerActionHandler]: renders and posts the "Sites
 * available" alert for a watch's openings via [SlackNotificationService].
 * Returns the service's own success flag so a delivery failure keeps a
 * `stopWhenTriggered` watch alive (the dispatcher's DONE transition gates
 * on this return value). Channel-override extraction from `triggerConfig`
 * lives here; the service falls back to its configured default channel when
 * the override is absent or blank.
 */
internal class SlackNotifyHandler(
    private val slack: SlackNotificationService,
    private val appRootUrl: String?,
) : TriggerActionHandler {
    override val kind: String = KIND

    override suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<TriggerOpening>,
    ): Boolean =
        slack.sendWatchOpenings(
            watchId = watch.id,
            startDate = watch.startDate,
            endDate = watch.endDate,
            openings = openings.map { it.notification },
            channel = watch.channelOverride(),
            appRootUrl = appRootUrl,
        )

    companion object {
        const val KIND = "slack_notify"
    }
}
