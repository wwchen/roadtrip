package ca.floo.roadtrip.service.notification

/**
 * Business seam for delivering a Slack notification. Callers depend on this
 * interface — not on the [ca.floo.roadtrip.clients.slack.SlackClient] transport
 * — so notification policy (channel routing, formatting, future retries or
 * alternate transports) can evolve without touching every call site, and tests
 * substitute a trivial fake instead of a live workspace.
 */
interface SlackNotificationService {
    /**
     * Sends [text] to [channel], or to the service's configured default channel
     * when [channel] is null. Never throws: a delivery failure — or having no
     * channel to send to — is surfaced as `false` so a notification problem
     * can't break the caller's flow.
     */
    suspend fun sendMessage(
        text: String,
        channel: String? = null,
    ): Boolean
}
