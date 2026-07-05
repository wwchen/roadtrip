package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.LocalDate

/**
 * Default [SlackNotificationService] backed by the Slack HTTP transport. Owns
 * channel policy: a caller may name a channel, otherwise the message goes to
 * the configured default channel. It also owns the mapping from domain openings
 * to the Slack message body (via [SlackContentAvailabilityRenderer]), so feature
 * callers deal only in domain types.
 *
 * [config] is null when Slack is unconfigured — a first-class "disabled" state,
 * not an error. The service owns the [SlackClient] it builds from [config] (and
 * its shutdown, via [close]); with no config there is no client. In that state
 * every send logs why it was skipped and returns `false`, so a disabled
 * workspace is visible in the logs rather than a silent no-op, without breaking
 * any caller's flow.
 *
 * [client] is injectable for tests (the enabled send path); production always
 * uses the default built from [config].
 */
class SlackNotificationServiceImpl(
    private val config: SlackConfig?,
    private val client: SlackClient? = config?.let { SlackClient(it) },
) : SlackNotificationService,
    Closeable {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        channel: String?,
    ): Boolean {
        val (fallback, blocks) = SlackContentWatchStatusRenderer.render(notice)
        return send(channel, fallback, blocks)
    }

    override suspend fun sendWatchOpenings(
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        channel: String?,
        controls: WatchControlLinks?,
    ): Boolean {
        if (openings.isEmpty()) return false
        val (fallback, blocks) = SlackContentAvailabilityRenderer.openings(startDate, endDate, openings, controls)
        return send(channel, fallback, blocks)
    }

    /** The single send gate: no-ops (logging why) when Slack is disabled,
     *  otherwise posts [text] plus optional [blocks] to [channel] or the default. */
    private suspend fun send(
        channel: String?,
        text: String,
        blocks: List<SlackBlockDto>?,
    ): Boolean {
        if (config == null || client == null) {
            log.warn("Slack disabled ({} / {} unset); message not sent: {}", SlackConfig.TOKEN_ENV, SlackConfig.CHANNEL_ENV, text)
            return false
        }
        return client.postMessage(channel ?: config.defaultChannel, text, blocks)
    }

    /** Releases the owned [SlackClient]'s HTTP client. Call on app shutdown. */
    override fun close() {
        client?.close()
    }
}
