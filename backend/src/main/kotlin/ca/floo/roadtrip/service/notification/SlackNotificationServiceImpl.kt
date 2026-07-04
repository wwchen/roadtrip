package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackClient

/**
 * Default [SlackNotificationService] backed by the Slack HTTP transport. Thin
 * today — it forwards to [SlackClient.postMessage] — but it is the seam where
 * notification policy (default channel, formatting, retries, alternate
 * transports) will land, keeping that logic out of both the transport and the
 * feature callers.
 */
class SlackNotificationServiceImpl(
    private val client: SlackClient,
) : SlackNotificationService {
    override suspend fun sendMessage(
        channel: String,
        text: String,
    ): Boolean = client.postMessage(channel, text)
}
