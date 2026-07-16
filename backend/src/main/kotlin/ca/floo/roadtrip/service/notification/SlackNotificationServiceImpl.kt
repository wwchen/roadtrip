package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackBlocks
import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.LocalDate

private const val ATC_STATUS_COMPLETED = "completed"
private const val MAX_JSON_REPORT_CHARS = 2500
private const val TRUNCATED_REPORT_SUFFIX = "\n..."

private val slackJson =
    Json {
        prettyPrint = true
    }

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

    override suspend fun sendAtcResult(
        watchId: Long,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        channel: String?,
    ): Boolean {
        val text = "ATC $status for watch #$watchId ($vendor)"
        val blocks =
            mutableListOf(
                SlackBlocks.header("ATC $status"),
                SlackBlocks.fields(
                    listOf(
                        "*Watch*\n#$watchId",
                        "*Status*\n$status",
                        "*Vendor*\n$vendor",
                    ),
                ),
                SlackBlocks.section("*Request body*\n```${formatJsonReport(request)}```"),
            )
        if (response != null) {
            blocks += SlackBlocks.section("*Companion response*\n```${formatJsonReport(response)}```")
        }
        val attachments =
            listOf(
                SlackAttachmentDto(
                    color = atcResultColor(status),
                    fallback = text,
                    blocks = blocks,
                ),
            )
        return send(channel, text, attachments)
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

    override suspend fun postResponseStaleWatch(
        responseUrl: String,
        watchId: Long,
    ): Boolean {
        val (fallback, attachments) = SlackContentStaleWatchRenderer.render(watchId)
        if (client == null) {
            log.warn("Slack disabled; stale response_url update skipped: {}", fallback)
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
            log.warn("Slack disabled (bot-token/default-channel unset); message not sent: {}", text)
            return false
        }
        return client.postMessage(channel ?: config.defaultChannel, text, attachments = attachments)
    }

    /** Releases the owned [SlackClient]'s HTTP client. Call on app shutdown. */
    override fun close() {
        client?.close()
    }

    private fun atcResultColor(status: String): String =
        if (status == ATC_STATUS_COMPLETED) {
            SlackWatchCard.COLOR_AVAIL
        } else {
            SlackWatchCard.COLOR_ERROR
        }

    private fun formatJsonReport(body: JsonObject): String {
        val rendered = slackJson.encodeToString(body)
        if (rendered.length <= MAX_JSON_REPORT_CHARS) return rendered
        return rendered.take(MAX_JSON_REPORT_CHARS - TRUNCATED_REPORT_SUFFIX.length) +
            TRUNCATED_REPORT_SUFFIX
    }
}
