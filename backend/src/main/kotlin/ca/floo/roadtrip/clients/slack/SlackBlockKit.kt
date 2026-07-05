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

/** A link button in an actions block: a [label], the [url] it opens, and
 *  whether it renders as the green primary CTA. Callers pass these to
 *  [SlackBlocks.actions] rather than building [SlackButtonDto]s directly. */
data class ButtonSpec(
    val label: String,
    val url: String,
    val primary: Boolean = false,
)

/** Semantic builders so callers express intent (header, fields, section,
 *  buttons) instead of sprinkling Block Kit `type` strings. */
object SlackBlocks {
    /** Max buttons Slack accepts in one actions block; more must be split across blocks. */
    const val ACTIONS_MAX_ELEMENTS = 25

    fun header(text: String): SlackBlockDto = SlackBlockDto(type = "header", text = plain(text))

    /** A section rendered as a 2-column field grid (Slack lays fields out in pairs). */
    fun fields(fields: List<String>): SlackBlockDto = SlackBlockDto(type = "section", fields = fields.map(::mrkdwn))

    fun section(text: String): SlackBlockDto = SlackBlockDto(type = "section", text = mrkdwn(text))

    /** An actions block of link buttons. Caller must keep [buttons] within
     *  [ACTIONS_MAX_ELEMENTS]; chunk longer lists into multiple blocks. */
    fun actions(buttons: List<ButtonSpec>): SlackBlockDto =
        SlackBlockDto(
            type = "actions",
            elements =
                buttons.map { b ->
                    SlackButtonDto(type = "button", text = plain(b.label), url = b.url, style = if (b.primary) "primary" else null)
                },
        )

    fun primaryButton(
        label: String,
        url: String,
    ): SlackBlockDto = actions(listOf(ButtonSpec(label, url, primary = true)))

    private fun plain(text: String): SlackTextDto = SlackTextDto(type = "plain_text", text = text, emoji = true)

    private fun mrkdwn(text: String): SlackTextDto = SlackTextDto(type = "mrkdwn", text = text)
}
