package ca.floo.roadtrip.service.notification.common

/**
 * A concrete destination for a watch notification. Watch trigger config is
 * translated into these targets at the availability boundary; transports never
 * inspect the watch row directly.
 */
sealed interface NotificationTarget {
    data class Slack(
        val channel: String? = null,
    ) : NotificationTarget

    data class Email(
        val recipients: List<String> = emptyList(),
    ) : NotificationTarget
}
