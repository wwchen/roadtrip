package ca.floo.roadtrip.clients.slack

import kotlinx.serialization.Serializable

/**
 * Minimal Slack Block Kit payload model — only the block types we send. Callers
 * build these through [SlackBlocks]; [SlackClient] serializes them onto
 * `chat.postMessage`. Keeping the Block Kit `type` vocabulary here (not at call
 * sites) is the one place that knows Slack's wire shape.
 */
@Serializable
data class SlackBlockDto(
    val type: String,
    val text: SlackTextDto? = null,
    val fields: List<SlackTextDto>? = null,
    val elements: List<SlackButtonDto>? = null,
)

@Serializable
data class SlackTextDto(
    val type: String,
    val text: String,
    val emoji: Boolean? = null,
)

@Serializable
data class SlackButtonDto(
    val type: String,
    val text: SlackTextDto,
    val url: String,
    val style: String? = null,
)

/** Semantic builders so callers express intent (header, fields, section,
 *  button) instead of sprinkling Block Kit `type` strings. */
object SlackBlocks {
    fun header(text: String): SlackBlockDto = SlackBlockDto(type = "header", text = plain(text))

    /** A section rendered as a 2-column field grid (Slack lays fields out in pairs). */
    fun fields(fields: List<String>): SlackBlockDto = SlackBlockDto(type = "section", fields = fields.map(::mrkdwn))

    fun section(text: String): SlackBlockDto = SlackBlockDto(type = "section", text = mrkdwn(text))

    fun primaryButton(
        label: String,
        url: String,
    ): SlackBlockDto =
        SlackBlockDto(
            type = "actions",
            elements = listOf(SlackButtonDto(type = "button", text = plain(label), url = url, style = "primary")),
        )

    private fun plain(text: String): SlackTextDto = SlackTextDto(type = "plain_text", text = text, emoji = true)

    private fun mrkdwn(text: String): SlackTextDto = SlackTextDto(type = "mrkdwn", text = text)
}
