package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.LocalDate

/**
 * Default [SlackNotificationService] backed by the Slack HTTP transport. Owns
 * channel policy: a caller may name a channel, otherwise the message goes to
 * the configured default channel. It also owns the mapping from domain data
 * to the Slack message body (via [SlackContentAvailabilityRenderer] and
 * [SlackContentWatchStatusRenderer]), so feature callers deal only in domain
 * types.
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
        val (fallback, attachments) = SlackContentWatchStatusRenderer.render(notice)
        return send(channel, fallback, attachments)
    }

    override suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        channel: String?,
        appRootUrl: String?,
    ): Boolean {
        if (openings.isEmpty()) return false
        val (fallback, attachments) =
            SlackContentAvailabilityRenderer.openings(watchId, startDate, endDate, openings, appRootUrl)
        return send(channel, fallback, attachments)
    }

    override suspend fun postResponseWatchStatus(
        responseUrl: String,
        notice: WatchStatusNotice,
    ): Boolean {
        // response_url posts don't need a bot token or a configured channel —
        // they use the one-shot URL Slack returned in the interaction payload.
        // Still gate on `client` since a disabled workspace has no HTTP client
        // to talk to; that's the same "Slack not configured" fallback path.
        val (fallback, attachments) = SlackContentWatchStatusRenderer.render(notice)
        if (client == null) {
            log.warn("Slack disabled; response_url update skipped: {}", fallback)
            return false
        }
        return client.postResponse(responseUrl, fallback, attachments = attachments)
    }

    /** The single send gate: no-ops (logging why) when Slack is disabled,
     *  otherwise posts [text] plus [attachments] to [channel] or the default. */
    private suspend fun send(
        channel: String?,
        text: String,
        attachments: List<SlackAttachmentDto>,
    ): Boolean {
        if (config == null || client == null) {
            log.warn("Slack disabled ({} / {} unset); message not sent: {}", SlackConfig.TOKEN_ENV, SlackConfig.CHANNEL_ENV, text)
            return false
        }
        return client.postMessage(channel ?: config.defaultChannel, text, attachments = attachments)
    }

    /** Releases the owned [SlackClient]'s HTTP client. Call on app shutdown. */
    override fun close() {
        client?.close()
    }
}
