package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackBlocks
import ca.floo.roadtrip.clients.slack.SlackClient
import ca.floo.roadtrip.config.SlackConfig
import ca.floo.roadtrip.service.notification.common.NotificationService
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.LocalDate

private const val ATC_STATUS_COMPLETED = "completed"
private const val MAX_JSON_REPORT_CHUNK_CHARS = 2600
private const val JSON_REPORT_CHUNK_MIN_CHARS = 1

private val slackJson = Json
private val specialJsonSpaceChars = Regex("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]")

/**
 * Slack notification transport. It handles only [NotificationTarget.Slack];
 * [ca.floo.roadtrip.service.notification.common.NotificationFanout] owns
 * picking this service from the target list.
 *
 * [config] is null when Slack is unconfigured. That disabled state returns
 * `false` with a log line instead of throwing into availability polling.
 *
 * [client] is injectable for tests; production builds it from config.
 */
class SlackNotificationService(
    private val config: SlackConfig?,
    private val client: SlackClient? = config?.let { SlackClient(it) },
) : NotificationService,
    SlackResponseSender,
    Closeable {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun canHandle(target: NotificationTarget): Boolean = target is NotificationTarget.Slack

    override suspend fun sendWatchStatus(
        notice: WatchStatusNotice,
        target: NotificationTarget,
    ): Boolean {
        val slackTarget = target as? NotificationTarget.Slack ?: return false
        val (fallback, attachments) = SlackContentWatchStatusRenderer.render(notice)
        return send(slackTarget.channel, fallback, attachments)
    }

    override suspend fun sendWatchOpenings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        target: NotificationTarget,
        appRootUrl: String?,
    ): Boolean {
        if (openings.isEmpty()) return false
        val slackTarget = target as? NotificationTarget.Slack ?: return false
        val (fallback, attachments) =
            SlackContentAvailabilityRenderer.openings(watchId, startDate, endDate, openings, appRootUrl)
        return send(slackTarget.channel, fallback, attachments)
    }

    override suspend fun sendAtcResult(
        watchId: Long,
        vendor: String,
        status: String,
        request: JsonObject,
        response: JsonObject?,
        target: NotificationTarget,
    ): Boolean {
        val slackTarget = target as? NotificationTarget.Slack ?: return false
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
            )
        blocks += jsonReportBlocks("Request body", request)
        if (response != null) {
            blocks += atcDiagnosticBlocks(response)
            blocks += jsonReportBlocks("Companion response", response)
        }
        val attachments =
            listOf(
                SlackAttachmentDto(
                    color = atcResultColor(status),
                    fallback = text,
                    blocks = blocks,
                ),
            )
        return send(slackTarget.channel, text, attachments)
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

    private fun jsonReportBlocks(
        title: String,
        body: JsonObject,
    ): List<SlackBlockDto> {
        val chunks = splitJsonReport(formatJsonReport(body))
        return chunks.mapIndexed { index, chunk ->
            val part = if (chunks.size == 1) "" else " (${index + 1}/${chunks.size})"
            SlackBlocks.section("*$title$part*\n```$chunk```")
        }
    }

    private fun formatJsonReport(body: JsonObject): String = sanitizeSlackText(slackJson.encodeToString(body))

    private fun sanitizeSlackText(value: String): String = specialJsonSpaceChars.replace(value, " ")

    private fun atcDiagnosticBlocks(response: JsonObject): List<SlackBlockDto> {
        val fields =
            listOfNotNull(
                response.stringValue("error")?.let { "*Error*\n`$it`" },
                response.stringValue("detail")?.let { "*Detail*\n$it" },
                response.stringValue("booking_url")?.let { "*Booking URL*\n$it" },
                cartCheckSummary(response.objectValue("cart_check"))?.let { "*Cart check*\n$it" },
                latestScreenshotSummary(response.arrayValue("screenshots"))?.let { "*Latest screenshot*\n`$it`" },
                response.arrayValue("logs")?.size?.let { "*Log lines*\n$it" },
            )
        return if (fields.isEmpty()) emptyList() else listOf(SlackBlocks.fields(fields))
    }

    private fun splitJsonReport(rendered: String): List<String> {
        if (rendered.length <= MAX_JSON_REPORT_CHUNK_CHARS) return listOf(rendered)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        rendered.lineSequence().forEach { line ->
            appendJsonReportLine(chunks, current, line)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.ifEmpty { listOf(rendered) }
    }

    private fun appendJsonReportLine(
        chunks: MutableList<String>,
        current: StringBuilder,
        line: String,
    ) {
        val separatorLength = if (current.isEmpty()) 0 else 1
        if (current.length + separatorLength + line.length <= MAX_JSON_REPORT_CHUNK_CHARS) {
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
            return
        }
        if (current.isNotEmpty()) {
            chunks += current.toString()
            current.clear()
        }
        if (line.length <= MAX_JSON_REPORT_CHUNK_CHARS) {
            current.append(line)
            return
        }
        val chunkSize = MAX_JSON_REPORT_CHUNK_CHARS.coerceAtLeast(JSON_REPORT_CHUNK_MIN_CHARS)
        line
            .chunked(chunkSize)
            .forEach(chunks::add)
    }

    private fun cartCheckSummary(cartCheck: JsonObject?): String? {
        if (cartCheck == null) return null
        val parts =
            listOfNotNull(
                cartCheck.stringValue("reason")?.let { "reason=`$it`" },
                cartCheck.stringValue("status")?.let { "status=$it" },
                cartCheck.stringValue("reservation_count")?.let { "reservations=$it" },
                cartCheck.stringValue("response_signal")?.let { "response_signal=$it" },
            )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun latestScreenshotSummary(screenshots: JsonArray?): String? =
        screenshots
            ?.mapNotNull { it as? JsonObject }
            ?.lastOrNull()
            ?.stringValue("screenshot_url")

    private fun JsonObject.stringValue(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull?.let(::sanitizeSlackText)

    private fun JsonObject.objectValue(name: String): JsonObject? = get(name) as? JsonObject

    private fun JsonObject.arrayValue(name: String): JsonArray? = get(name) as? JsonArray
}
