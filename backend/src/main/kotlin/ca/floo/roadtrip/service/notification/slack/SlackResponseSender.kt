package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.service.notification.common.WatchStatusNotice

/**
 * Slack-only response URL surface for interactivity updates. This stays out of
 * [NotificationService] because response URLs are not watch notification
 * targets and have no email/SMS equivalent.
 */
interface SlackResponseSender {
    /**
     * Updates a Slack card in place after a user pressed an interactive button
     * (pause / resume / delete): re-renders [notice] and posts it to Slack's
     * `response_url` from the interaction payload. Never throws.
     */
    suspend fun postResponseWatchStatus(
        responseUrl: String,
        notice: WatchStatusNotice,
    ): Boolean

    /**
     * Replaces a clicked Slack card whose watch id no longer resolves. This is
     * the stale-card path for deleted/reset watches: no mutation happened, but
     * the user still gets immediate feedback and the dead buttons disappear.
     */
    suspend fun postResponseStaleWatch(
        responseUrl: String,
        watchId: Long,
    ): Boolean
}
