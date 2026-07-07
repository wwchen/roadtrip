package ca.floo.roadtrip.clients.slack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal Slack Block Kit payload model — only the block types we send. Callers
 * build these through [SlackBlocks]; [SlackClient] serializes them onto
 * `chat.postMessage`. Keeping the Block Kit `type` vocabulary here (not at call
 * sites) is the one place that knows Slack's wire shape.
 *
 * The [elements] field is heterogeneous by block type — a list of button
 * objects on an `actions` block, a list of mrkdwn text objects on a `context`
 * block — so it's typed as [JsonElement] and built by the [SlackBlocks]
 * helpers. Section/header blocks use [text] / [fields] and leave [elements]
 * null.
 */
@Serializable
data class SlackBlockDto(
    val type: String,
    val text: SlackTextDto? = null,
    val fields: List<SlackTextDto>? = null,
    val elements: JsonElement? = null,
)

@Serializable
data class SlackTextDto(
    val type: String,
    val text: String,
    val emoji: Boolean? = null,
)

/** Legacy attachment envelope — the one styling lever Slack gives us for
 *  outbound messages. The [color] hex renders as a vertical bar down the
 *  attachment's left edge; [blocks] carries the modern Block Kit body. Every
 *  watch notification wraps its blocks in exactly one attachment so the bar
 *  applies to the whole card. */
@Serializable
data class SlackAttachmentDto(
    val color: String,
    val blocks: List<SlackBlockDto>,
)

/** A Slack button element used inside an `actions` block. Buttons render both
 *  in the client UI and (for URL buttons) fire a redirect on click; interactive
 *  buttons additionally fire a `block_actions` payload to the app's Slack
 *  interactivity Request URL, which
 *  [ca.floo.roadtrip.service.notification.SlackInteractivityRoute] verifies and
 *  routes by [actionId] + [value].
 *
 *  Set [url] for URL buttons (opens the URL in the browser); leave it null for
 *  purely-interactive buttons. Both cases still require [actionId] — Slack
 *  makes it mandatory even for URL-only buttons — and it uniquely names the
 *  button across a message.
 *
 *  [style] is limited by Slack to `default` (null), `primary` (green), or
 *  `danger` (red); [confirm] attaches a native Slack confirmation dialog. */
data class SlackButtonSpec(
    val label: String,
    val actionId: String,
    val url: String? = null,
    val value: String? = null,
    val style: Style = Style.DEFAULT,
    val emoji: Boolean = true,
    val confirm: SlackConfirmSpec? = null,
) {
    /** Slack ships only these three visual tiers; there is no brand-blue filled
     *  button. `DEFAULT` renders no `style` field on the wire. */
    enum class Style(
        val wire: String?,
    ) {
        DEFAULT(null),
        PRIMARY("primary"),
        DANGER("danger"),
    }
}

/** Slack native confirm dialog attached to a button. Fires between click and
 *  action — the interactivity payload only arrives when the user confirms. */
data class SlackConfirmSpec(
    val title: String,
    val text: String,
    val confirm: String,
    val deny: String,
    val danger: Boolean = false,
)

/** Semantic builders so callers express intent (header, fields, section,
 *  actions, context) instead of sprinkling Block Kit `type` strings. */
object SlackBlocks {
    fun header(text: String): SlackBlockDto = SlackBlockDto(type = "header", text = plain(text))

    /** A section rendered as a 2-column field grid (Slack lays fields out in pairs). */
    fun fields(fields: List<String>): SlackBlockDto = SlackBlockDto(type = "section", fields = fields.map(::mrkdwn))

    fun section(text: String): SlackBlockDto = SlackBlockDto(type = "section", text = mrkdwn(text))

    /** An actions row of button elements. Order in the row = order in the list. */
    fun actions(buttons: List<SlackButtonSpec>): SlackBlockDto =
        SlackBlockDto(
            type = "actions",
            elements =
                buildJsonArray {
                    buttons.forEach { add(buttonJson(it)) }
                },
        )

    /** A context block — small muted text below the actions row, for the
     *  "checked X ago" sub-line. Only mrkdwn text is supported; if Slack ever
     *  needs image elements too, extend here. */
    fun context(text: String): SlackBlockDto =
        SlackBlockDto(
            type = "context",
            elements =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "mrkdwn")
                            put("text", text)
                        },
                    )
                },
        )

    private fun buttonJson(button: SlackButtonSpec): JsonElement =
        buildJsonObject {
            put("type", "button")
            putJsonObject("text") {
                put("type", "plain_text")
                put("text", button.label)
                put("emoji", button.emoji)
            }
            put("action_id", button.actionId)
            button.url?.let { put("url", it) }
            button.value?.let { put("value", it) }
            button.style.wire?.let { put("style", it) }
            button.confirm?.let { c ->
                putJsonObject("confirm") {
                    putJsonObject("title") {
                        put("type", "plain_text")
                        put("text", c.title)
                    }
                    putJsonObject("text") {
                        put("type", "mrkdwn")
                        put("text", c.text)
                    }
                    putJsonObject("confirm") {
                        put("type", "plain_text")
                        put("text", c.confirm)
                    }
                    putJsonObject("deny") {
                        put("type", "plain_text")
                        put("text", c.deny)
                    }
                    if (c.danger) put("style", "danger")
                }
            }
        }

    private fun plain(text: String): SlackTextDto = SlackTextDto(type = "plain_text", text = text, emoji = true)

    private fun mrkdwn(text: String): SlackTextDto = SlackTextDto(type = "mrkdwn", text = text)
}
