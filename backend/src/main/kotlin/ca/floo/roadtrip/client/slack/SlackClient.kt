package ca.floo.roadtrip.client.slack

import ca.floo.roadtrip.config.SlackConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
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
 * Provider-neutral result of a Slack `auth.test` call.
 * Surfaced through the client interface without leaking Slack wire types.
 */
data class SlackIdentity(
    val teamName: String?,
    val botName: String?,
)

/**
 * Outbound Slack `chat.postMessage` transport — the only thing that talks to
 * Slack over the wire. Business callers go through
 * [ca.floo.roadtrip.service.notification.slack.SlackNotificationService]; this httpClient
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
    private val httpClient: HttpClient = HttpClient(CIO) { engine { requestTimeout = SLACK_REQUEST_TIMEOUT_MS } },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Calls Slack `auth.test` with the given [token]. Returns a [SlackIdentity]
     * when Slack accepts the token (`ok:true`), or null when Slack rejects it
     * (`ok:false`, e.g. `invalid_auth`) or the request fails.
     */
    open suspend fun authTest(token: String): SlackIdentity? =
        runCatching { callAuthTest(token) }
            .onFailure { log.error("Slack auth.test failed: {}", it.message) }
            .getOrNull()

    /**
     * Posts one message to [channel] using the global config bot token. [text] is
     * the notification fallback (shown in notifications and by clients that don't
     * render blocks); either [attachments] or [blocks] carries the rich body.
     * Prefer attachments for cards that need the color-bar accent (watch alerts do).
     * Returns true only on Slack `ok:true`.
     */
    open suspend fun postMessage(
        channel: String,
        text: String,
        blocks: List<SlackBlockDto>? = null,
        attachments: List<SlackAttachmentDto>? = null,
    ): Boolean = postMessage(config.botToken, channel, text, blocks, attachments)

    /**
     * Posts one message to [channel] using the caller-supplied [token].
     * Allows per-user bot tokens to be used instead of the global config token.
     * Returns true only on Slack `ok:true`.
     */
    open suspend fun postMessage(
        token: String,
        channel: String,
        text: String,
        blocks: List<SlackBlockDto>? = null,
        attachments: List<SlackAttachmentDto>? = null,
    ): Boolean =
        runCatching { post(token, channel, text, blocks, attachments) }
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

    private suspend fun callAuthTest(token: String): SlackIdentity? {
        val resp =
            httpClient.get(SLACK_AUTH_TEST_URL) {
                header("Authorization", "Bearer $token")
            }
        val parsed =
            Json.parseToJsonElement(resp.bodyAsText()) as? JsonObject ?: run {
                log.warn("Slack auth.test returned unparseable response")
                return null
            }
        val ok = (parsed.get("ok") as? JsonPrimitive)?.content == "true"
        if (!ok) {
            val err = (parsed.get("error") as? JsonPrimitive)?.content ?: resp.bodyAsText()
            log.warn("Slack auth.test not ok: {}", err)
            return null
        }
        return SlackIdentity(
            teamName = (parsed.get("team") as? JsonPrimitive)?.content,
            botName = (parsed.get("user") as? JsonPrimitive)?.content,
        )
    }

    private suspend fun post(
        token: String,
        channel: String,
        text: String,
        blocks: List<SlackBlockDto>?,
        attachments: List<SlackAttachmentDto>?,
    ): Boolean {
        val body =
            slackJson.encodeToString(
                SlackPostMessageDto.serializer(),
                SlackPostMessageDto(
                    channel = channel,
                    text = text.takeIf { attachments.isNullOrEmpty() },
                    blocks = blocks,
                    attachments = attachments.withFallback(text),
                ),
            )
        val resp =
            httpClient.post(SLACK_POST_MESSAGE_URL) {
                header("Authorization", "Bearer $token")
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
                SlackResponseMessageDto(
                    text = text.takeIf { attachments.isNullOrEmpty() },
                    blocks = blocks,
                    attachments = attachments.withFallback(text),
                    replaceOriginal = true,
                ),
            )
        val resp =
            httpClient.post(responseUrl) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val ok = resp.status.value in 200..299
        if (!ok) log.warn("Slack response_url returned {}: {}", resp.status.value, resp.bodyAsText())
        return ok
    }

    /** Releases the underlying HTTP httpClient. Call on app shutdown. */
    fun close() = httpClient.close()

    private fun List<SlackAttachmentDto>?.withFallback(text: String): List<SlackAttachmentDto>? =
        this?.mapIndexed { index, attachment ->
            if (index == 0 && attachment.fallback == null) attachment.copy(fallback = text) else attachment
        }
}

private const val SLACK_POST_MESSAGE_URL = "https://slack.com/api/chat.postMessage"
private const val SLACK_AUTH_TEST_URL = "https://slack.com/api/auth.test"
private const val SLACK_REQUEST_TIMEOUT_MS = 8_000L

@Serializable
private data class SlackPostMessageDto(
    val channel: String,
    val text: String? = null,
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
    val text: String? = null,
    val blocks: List<SlackBlockDto>? = null,
    val attachments: List<SlackAttachmentDto>? = null,
    @SerialName("replace_original")
    val replaceOriginal: Boolean = false,
)
