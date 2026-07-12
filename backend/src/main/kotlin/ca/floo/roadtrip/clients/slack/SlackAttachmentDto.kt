package ca.floo.roadtrip.clients.slack

import kotlinx.serialization.Serializable

/** Legacy attachment envelope — the one styling lever Slack gives us for
 *  outbound messages. The [color] hex renders as a vertical bar down the
 *  attachment's left edge; [blocks] carries the modern Block Kit body. Every
 *  watch notification wraps its blocks in exactly one attachment so the bar
 *  applies to the whole card. */
@Serializable
data class SlackAttachmentDto(
    val color: String,
    val blocks: List<SlackBlockDto>,
    val fallback: String? = null,
)
