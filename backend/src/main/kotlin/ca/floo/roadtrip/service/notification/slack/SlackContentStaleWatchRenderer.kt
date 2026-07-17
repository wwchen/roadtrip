package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.client.slack.SlackAttachmentDto
import ca.floo.roadtrip.client.slack.SlackBlocks

/**
 * Renders the terminal in-place response for a Slack card whose interactive
 * button points at a watch row that no longer exists.
 */
object SlackContentStaleWatchRenderer {
    private const val FALLBACK = "Watch no longer exists"
    private const val HEADER = "⚠️ Watch no longer exists"
    private const val BODY_PREFIX = "This Slack card points to watch"
    private const val BODY_SUFFIX = "but that watch is already gone. No change was applied."
    private const val CONTEXT = "Create a fresh watch if you still want alerts for this window."

    fun render(watchId: Long): Pair<String, List<SlackAttachmentDto>> {
        val blocks =
            listOf(
                SlackBlocks.section("*$HEADER*"),
                SlackBlocks.section("$BODY_PREFIX `$watchId`, $BODY_SUFFIX"),
                SlackBlocks.context(CONTEXT),
            )
        return FALLBACK to listOf(SlackAttachmentDto(color = SlackWatchCard.COLOR_MUTED, blocks = blocks))
    }
}
