package ca.floo.roadtrip.client.slack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
