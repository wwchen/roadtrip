package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget

/**
 * Aggregate notification trigger action. A watch can ask for multiple
 * notification trigger kinds (`slack_notify`, `email_notify`); this handler
 * translates those kinds and trigger config into concrete notification targets
 * and sends the opening alert once through [NotificationSender].
 */
internal class NotifyTriggerActionHandler(
    private val notifications: NotificationSender,
    private val appRootUrl: String?,
) : TriggerActionHandler {
    override val kinds: Set<String> =
        setOf(
            AvailabilityTriggerKinds.SLACK_NOTIFY,
            AvailabilityTriggerKinds.EMAIL_NOTIFY,
        )

    override suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<TriggerOpening>,
    ): Boolean {
        val targets = watch.openingNotificationTargets()
        if (targets.isEmpty()) return false
        return notifications.sendWatchOpenings(
            watchId = watch.id,
            startDate = watch.startDate,
            endDate = watch.endDate,
            openings = openings.map { it.notification },
            targets = targets,
            appRootUrl = appRootUrl,
        )
    }
}

internal fun AvailabilityWatchRepo.Watch.openingNotificationTargets(): List<NotificationTarget> =
    buildList {
        if (AvailabilityTriggerKinds.SLACK_NOTIFY in triggerKinds) {
            add(NotificationTarget.Slack(channel = channelOverride()))
        }
        if (AvailabilityTriggerKinds.EMAIL_NOTIFY in triggerKinds) {
            add(NotificationTarget.Email(recipients = emailRecipients()))
        }
    }
