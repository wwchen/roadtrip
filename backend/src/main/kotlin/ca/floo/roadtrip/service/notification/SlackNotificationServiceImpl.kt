package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackClient
import org.slf4j.LoggerFactory

/**
 * Default [SlackNotificationService] backed by the Slack HTTP transport. Owns
 * channel policy: a caller may name a channel, otherwise the message goes to
 * [defaultChannel]. Thin beyond that — it forwards to [SlackClient.postMessage]
 * — but it is the seam where further notification policy (formatting, retries,
 * alternate transports) will land, keeping that logic out of both the transport
 * and the feature callers.
 */
class SlackNotificationServiceImpl(
    private val client: SlackClient,
    private val defaultChannel: String?,
) : SlackNotificationService {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun sendMessage(
        text: String,
        channel: String?,
    ): Boolean {
        val target = channel ?: defaultChannel
        if (target == null) {
            log.warn("Slack message dropped: no channel given and no default channel configured")
            return false
        }
        return client.postMessage(target, text)
    }
}
