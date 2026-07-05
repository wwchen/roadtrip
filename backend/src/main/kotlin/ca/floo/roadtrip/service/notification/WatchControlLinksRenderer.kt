package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.LinkSpec
import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackBlocks

/**
 * Renders a watch's [WatchControlLinks] into Slack link section(s) — the
 * "⏸ pause / ▶️ resume / 🗑 delete" controls shown on both the watch-status card
 * ([SlackContentWatchStatusRenderer]) and the openings alert
 * ([SlackContentAvailabilityRenderer]). Shared so the control labels and the
 * Slack `<url|label>` binding live in exactly one place rather than being
 * duplicated across the two renderers.
 *
 * The links are hyperlinks, never Block Kit `button` elements: a button would
 * fire an interaction payload Slack flags on an app with no interactivity
 * endpoint (see [ca.floo.roadtrip.clients.slack.LinkSpec]). Each opens the web
 * app's alerts panel focused on the watch, where the existing controls do the
 * PATCH/DELETE.
 */
internal object WatchControlLinksRenderer {
    /** Control labels are kept short for readability, not a Slack limit. */
    private const val LABEL_MAX = 75

    private const val PAUSE_LABEL = "⏸ pause watch"
    private const val RESUME_LABEL = "▶️ resume watch"
    private const val DELETE_LABEL = "🗑 delete watch"

    /**
     * Zero sections when [controls] is null or empty; otherwise the applicable
     * controls as inline mrkdwn hyperlinks, chunked to Slack's per-section cap
     * so the message can't be rejected.
     */
    fun sections(controls: WatchControlLinks?): List<SlackBlockDto> {
        if (controls == null || controls.isEmpty) return emptyList()
        val links =
            buildList {
                controls.pauseUrl?.let { add(LinkSpec(truncate(PAUSE_LABEL), it)) }
                controls.resumeUrl?.let { add(LinkSpec(truncate(RESUME_LABEL), it)) }
                controls.deleteUrl?.let { add(LinkSpec(truncate(DELETE_LABEL), it)) }
            }
        return links.chunked(SlackBlocks.LINKS_MAX_PER_SECTION).map(SlackBlocks::links)
    }

    private fun truncate(s: String): String = if (s.length <= LABEL_MAX) s else s.take(LABEL_MAX - 1).trimEnd() + "…"
}
