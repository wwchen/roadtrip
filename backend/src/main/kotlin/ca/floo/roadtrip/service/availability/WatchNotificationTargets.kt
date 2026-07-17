package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.notification.common.NotificationTarget

/**
 * Translates persisted watch trigger intent into concrete notification
 * destinations. The notification layer owns rendering and delivery; the
 * availability layer only decides which targets a watch opted into.
 */
internal fun AvailabilityWatchRepo.Watch.notificationTargets(): List<NotificationTarget> =
    buildList {
        if (AvailabilityTriggerKinds.SLACK_NOTIFY in triggerKinds) {
            add(NotificationTarget.Slack(channel = channelOverride()))
        }
        if (AvailabilityTriggerKinds.EMAIL_NOTIFY in triggerKinds) {
            add(NotificationTarget.Email(recipients = emailRecipients()))
        }
    }
