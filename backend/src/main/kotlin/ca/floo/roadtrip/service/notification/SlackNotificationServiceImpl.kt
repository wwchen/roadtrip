package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * Default [SlackNotificationService] backed by the Slack HTTP transport. Owns
 * channel policy: a caller may name a channel, otherwise the message goes to
 * the configured default channel. Thin beyond that — it forwards to
 * [SlackClient.postMessage] — but it is the seam where further notification
 * policy (formatting, retries, alternate transports) will land, keeping that
 * logic out of both the transport and the feature callers.
 *
 * [config] is null when Slack is unconfigured — a first-class "disabled" state,
 * not an error. The service owns the [SlackClient] it builds from [config] (and
 * its shutdown, via [close]); with no config there is no client. In that state
 * every send logs why it was skipped and returns `false`, so a disabled
 * workspace is visible in the logs rather than a silent no-op, without breaking
 * any caller's flow.
 */
class SlackNotificationServiceImpl(
    private val config: SlackConfig?,
) : SlackNotificationService,
    Closeable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: SlackClient? = config?.let { SlackClient(it) }

    override suspend fun sendMessage(
        text: String,
        channel: String?,
        blocks: List<SlackBlockDto>?,
    ): Boolean {
        if (config == null || client == null) {
            log.warn("Slack disabled ({} / {} unset); message not sent: {}", SlackConfig.TOKEN_ENV, SlackConfig.CHANNEL_ENV, text)
            return false
        }
        val target = channel ?: config.defaultChannel
        return client.postMessage(target, text, blocks)
    }

    /** Releases the owned [SlackClient]'s HTTP client. Call on app shutdown. */
    override fun close() {
        client?.close()
    }
}
