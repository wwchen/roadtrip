package ca.floo.roadtrip.service.notification.common

/**
 * A concrete destination for a watch notification. Watch trigger config is
 * translated into these targets at the availability boundary; transports never
 * inspect the watch row directly.
 */
sealed interface NotificationTarget {
    data class Slack(
        val channel: String? = null,
        // The owner's per-user Slack bot token (decrypted), when they have one
        // stored. Null => post via the global bot token. Set by
        // [ca.floo.roadtrip.service.availability.WatchNotificationTargetResolver]
        // so each owner's cards land in a channel that owner controls.
        val token: String? = null,
    ) : NotificationTarget

    data class Email(
        val recipients: List<String> = emptyList(),
    ) : NotificationTarget
}
