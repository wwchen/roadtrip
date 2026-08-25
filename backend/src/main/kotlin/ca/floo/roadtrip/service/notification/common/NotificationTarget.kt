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
        /**
         * The magic link for the watch this target was resolved for: a "manage
         * this alert" URL carrying a capability token, so the reader can change
         * or stop the watch straight from the mailbox the alert landed in.
         *
         * It rides on the target rather than being passed to every transport
         * because it is email-specific by construction — Slack cards already
         * carry interactive buttons scoped by delivery, and handing a bearer
         * token to a transport that does not need one only widens where it can
         * leak. Null when the web app root URL is unconfigured (no deep links)
         * or when no token could be minted.
         */
        val manageUrl: String? = null,
    ) : NotificationTarget
}
