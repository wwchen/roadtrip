package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackBlocks

/**
 * Maps a [WatchStatusNotice] to the Slack watch-status card — a
 * notification-fallback string plus the Block Kit body (header, scope / window
 * fields, a one-line status, and the dashboard deep-links). The Block Kit
 * sibling of the old single mrkdwn line, and a parallel to
 * [SlackContentAvailabilityRenderer]: the one place watch-status domain data
 * becomes Slack content, so the notification service owns the mapping and the
 * dispatcher only supplies data.
 *
 * Text is clamped to Slack's per-element limits ([FIELD_TEXT_MAX],
 * [SECTION_TEXT_MAX]); an over-long site name would otherwise make
 * `chat.postMessage` reject the whole message and the alert would never arrive.
 */
object SlackContentWatchStatusRenderer {
    // Slack Block Kit hard limits (chars). Exceeding either fails the post.
    private const val FIELD_TEXT_MAX = 2000
    private const val SECTION_TEXT_MAX = 3000

    /**
     * Renders [notice] into the fallback text (shown in notifications / by
     * clients that don't render blocks) paired with the Block Kit body.
     */
    fun render(notice: WatchStatusNotice): Pair<String, List<SlackBlockDto>> {
        val window = "${notice.startDate} → ${notice.endDate}"
        val single = notice.siteName != null

        val fieldLabel = if (single) "Site" else "Sites"
        val fieldValue =
            if (single) {
                "*${notice.siteName}*${notice.siteLoop?.let { " ($it)" }.orEmpty()}"
            } else {
                "${notice.siteCount}"
            }

        val links = linksLine(notice)
        val blocks =
            listOfNotNull(
                SlackBlocks.header(headerFor(notice.state)),
                SlackBlocks.fields(
                    listOf(
                        // Clamp the whole field (label + value): Slack's limit is on
                        // the field's text object, not just the value.
                        truncate("*$fieldLabel*\n$fieldValue", FIELD_TEXT_MAX),
                        "*Window*\n$window",
                    ),
                ),
                SlackBlocks.section(truncate(statusLine(notice.state), SECTION_TEXT_MAX)),
                links?.let { SlackBlocks.section(it) },
            )

        return fallback(notice, scopePlain(notice), window) to blocks
    }

    /** Big plain-text card title. Emoji leads so the message reads at a glance. */
    private fun headerFor(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING, WatchStatusNotice.State.UNCHECKED -> "👀 Watching for openings"
            WatchStatusNotice.State.PAUSED -> "⏸️ Watch paused"
            WatchStatusNotice.State.DONE -> "✅ Watch complete"
        }

    /** The one-line status sentence under the fields. */
    private fun statusLine(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING -> "Nothing available right now — I'll alert the moment a site opens."
            WatchStatusNotice.State.UNCHECKED -> "Availability not checked yet — I'll alert the moment a site opens."
            WatchStatusNotice.State.PAUSED -> "Paused — I won't alert until this watch is resumed."
            WatchStatusNotice.State.DONE -> "This watch is complete — no more alerts."
        }

    /** The deep-links as one mrkdwn section, or null when the watch carries none
     *  (both hosts unconfigured, or no POI-scoped targets). One line for the
     *  watch dashboard, then one line per watched POI pairing its map and grid
     *  links. A single POI reads plainly ("view on map", "availability grid");
     *  several are suffixed with the POI id so each is distinguishable. The
     *  `<url|label>` markup lives here, never in the domain [WatchStatusNotice]. */
    private fun linksLine(notice: WatchStatusNotice): String? {
        val single = notice.poiLinks.size == 1
        val parts =
            buildList {
                notice.dashboardUrl?.let { add("📊 <$it|watch dashboard>") }
                notice.poiLinks.forEach { poi ->
                    val suffix = if (single) "" else " ${poi.poiId}"
                    val poiLinks =
                        buildList {
                            poi.mapUrl?.let { add("🗺 <$it|view on map$suffix>") }
                            poi.gridUrl?.let { add("🗓 <$it|availability grid$suffix>") }
                        }
                    if (poiLinks.isNotEmpty()) add(poiLinks.joinToString("  ·  "))
                }
            }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Notification-fallback line — keeps the pre-blocks phrasing so it reads as
     *  a full sentence wherever blocks aren't rendered. */
    private fun fallback(
        notice: WatchStatusNotice,
        scope: String,
        window: String,
    ): String =
        when (notice.state) {
            WatchStatusNotice.State.WATCHING ->
                "👀 Watching $scope for $window — nothing available right now. I'll alert the moment a site opens."
            WatchStatusNotice.State.UNCHECKED ->
                "👀 Watching $scope for $window — availability not checked yet. I'll alert the moment a site opens."
            WatchStatusNotice.State.PAUSED ->
                "⏸️ Paused watching $scope for $window — I won't alert until it's resumed."
            WatchStatusNotice.State.DONE ->
                "✅ Done watching $scope for $window."
        }

    /** Unformatted scope for the fallback line: the single site's name (+ loop)
     *  or the plural count. */
    private fun scopePlain(notice: WatchStatusNotice): String =
        if (notice.siteName != null) {
            "${notice.siteName}${notice.siteLoop?.let { " ($it)" }.orEmpty()}"
        } else {
            "${notice.siteCount} sites"
        }

    /** Clamps [s] to [max] chars, replacing the tail with an ellipsis so the
     *  result is never longer than [max]. */
    private fun truncate(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"
}
