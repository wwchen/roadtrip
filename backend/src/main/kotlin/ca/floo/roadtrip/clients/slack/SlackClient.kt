package ca.floo.roadtrip.clients.slack

import ca.floo.roadtrip.config.SlackConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

private val slackJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * Outbound Slack `chat.postMessage` transport — the only thing that talks to
 * Slack over the wire. Business callers go through
 * [ca.floo.roadtrip.service.notification.SlackNotificationService]; this client
 * only knows how to put bytes on the network.
 *
 * A message is a channel + fallback text + either a rich [SlackAttachmentDto]
 * (color-barred card with blocks) or bare [SlackBlockDto]s. Every send sets
 * `unfurl_links: false, unfurl_media: false` so Recreation.gov / map URLs
 * embedded in the card don't render a giant preview under the message.
 *
 * [postMessage] and [postResponse] **never throw.** Any failure — bad token,
 * non-`ok` Slack response, network/timeout — is logged and surfaced as `false`,
 * so a delivery problem can never break the caller. `open` so tests can
 * substitute a fake without a live Slack workspace (mirrors
 * [ca.floo.roadtrip.service.ratelimit.VendorRateLimiter]).
 */
open class SlackClient(
    private val config: SlackConfig,
    private val client: HttpClient = HttpClient(CIO) { engine { requestTimeout = SLACK_REQUEST_TIMEOUT_MS } },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Posts one message to [channel]. [text] is the notification fallback (shown
     * in notifications and by clients that don't render blocks); either
     * [attachments] or [blocks] carries the rich body. Prefer attachments for
     * cards that need the color-bar accent (watch alerts do). Returns true only
     * on Slack `ok:true`.
     */
    open suspend fun postMessage(
        channel: String,
        text: String,
        blocks: List<SlackBlockDto>? = null,
        attachments: List<SlackAttachmentDto>? = null,
    ): Boolean =
        runCatching { post(channel, text, blocks, attachments) }
            .onFailure { log.error("Slack postMessage to {} failed: {}", channel, it.message) }
            .getOrDefault(false)

    /**
     * Replies to an interactivity payload's `response_url`, updating the
     * original message in place with a fresh card ([attachments] / [blocks]) and
     * a new fallback [text]. The URL is a one-shot token Slack returns in the
     * payload; no Bearer token is sent. Returns true only on HTTP 2xx.
     */
    open suspend fun postResponse(
        responseUrl: String,
        text: String,
        blocks: List<SlackBlockDto>? = null,
        attachments: List<SlackAttachmentDto>? = null,
    ): Boolean =
        runCatching { postToResponseUrl(responseUrl, text, blocks, attachments) }
            .onFailure { log.error("Slack response_url post failed: {}", it.message) }
            .getOrDefault(false)

    private suspend fun post(
        channel: String,
        text: String,
        blocks: List<SlackBlockDto>?,
        attachments: List<SlackAttachmentDto>?,
    ): Boolean {
        val body =
            slackJson.encodeToString(
                SlackPostMessageDto.serializer(),
                SlackPostMessageDto(channel = channel, text = text, blocks = blocks, attachments = attachments),
            )
        val resp =
            client.post(SLACK_POST_MESSAGE_URL) {
                header("Authorization", "Bearer ${config.botToken}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val parsed = Json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
        val ok = (parsed?.get("ok") as? JsonPrimitive)?.content == "true"
        if (!ok) {
            val err = (parsed?.get("error") as? JsonPrimitive)?.content ?: resp.bodyAsText()
            log.warn("Slack chat.postMessage to {} not ok: {}", channel, err)
        }
        return ok
    }

    private suspend fun postToResponseUrl(
        responseUrl: String,
        text: String,
        blocks: List<SlackBlockDto>?,
        attachments: List<SlackAttachmentDto>?,
    ): Boolean {
        val body =
            slackJson.encodeToString(
                SlackResponseMessageDto.serializer(),
                // replace_original overwrites the card the button lived on rather
                // than posting a followup — the whole point of the response URL
                // for a pause/resume/delete confirmation.
                SlackResponseMessageDto(text = text, blocks = blocks, attachments = attachments, replaceOriginal = true),
            )
        val resp =
            client.post(responseUrl) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val ok = resp.status.value in 200..299
        if (!ok) log.warn("Slack response_url returned {}: {}", resp.status.value, resp.bodyAsText())
        return ok
    }

    /** Releases the underlying HTTP client. Call on app shutdown. */
    fun close() = client.close()
}

private const val SLACK_POST_MESSAGE_URL = "https://slack.com/api/chat.postMessage"
private const val SLACK_REQUEST_TIMEOUT_MS = 8_000L

@Serializable
private data class SlackPostMessageDto(
    val channel: String,
    val text: String,
    val blocks: List<SlackBlockDto>? = null,
    val attachments: List<SlackAttachmentDto>? = null,
    // Turn OFF Slack's giant photo/link previews so a Recreation.gov Reserve URL
    // doesn't render a full-width park photo below the compact card.
    @SerialName("unfurl_links")
    val unfurlLinks: Boolean = false,
    @SerialName("unfurl_media")
    val unfurlMedia: Boolean = false,
)

@Serializable
private data class SlackResponseMessageDto(
    val text: String,
    val blocks: List<SlackBlockDto>? = null,
    val attachments: List<SlackAttachmentDto>? = null,
    @SerialName("replace_original")
    val replaceOriginal: Boolean = false,
)
