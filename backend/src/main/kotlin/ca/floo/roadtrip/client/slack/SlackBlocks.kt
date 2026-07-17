package ca.floo.roadtrip.client.slack

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
