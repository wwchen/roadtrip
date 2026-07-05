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
 * Slack over the wire. Text-only (no Block Kit): a message is a channel + a
 * string. Business callers go through
 * [ca.floo.roadtrip.service.notification.SlackNotificationService]; this client
 * only knows how to put bytes on the network.
 *
 * [postMessage] **never throws.** Any failure — bad token, non-`ok` Slack
 * response, network/timeout — is logged and surfaced as `false`, so a delivery
 * problem can never break the caller. `open` so tests can substitute a fake
 * without a live Slack workspace (mirrors
 * [ca.floo.roadtrip.service.ratelimit.VendorRateLimiter]).
 */
open class SlackClient(
    private val config: SlackConfig,
    private val client: HttpClient = HttpClient(CIO) { engine { requestTimeout = SLACK_REQUEST_TIMEOUT_MS } },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Posts one message to [channel]. [text] is the notification fallback (shown
     * in notifications and by clients that don't render blocks); [blocks], when
     * present, is the rich Block Kit body. Returns true only on Slack `ok:true`.
     */
    open suspend fun postMessage(
        channel: String,
        text: String,
        blocks: List<SlackBlockDto>? = null,
    ): Boolean =
        runCatching { post(channel, text, blocks) }
            .onFailure { log.error("Slack postMessage to {} failed: {}", channel, it.message) }
            .getOrDefault(false)

    private suspend fun post(
        channel: String,
        text: String,
        blocks: List<SlackBlockDto>?,
    ): Boolean {
        val body =
            slackJson.encodeToString(
                SlackPostMessageDto.serializer(),
                SlackPostMessageDto(channel = channel, text = text, blocks = blocks),
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
)
