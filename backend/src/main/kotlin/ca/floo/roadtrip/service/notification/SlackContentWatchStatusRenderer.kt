package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.ButtonSpec
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
    private const val BUTTON_TEXT_MAX = 75

    /**
     * Renders [notice] into the fallback text (shown in notifications / by
     * clients that don't render blocks) paired with the Block Kit body.
     */
    fun render(notice: WatchStatusNotice): Pair<String, List<SlackBlockDto>> {
        val window = "${notice.startDate} → ${notice.endDate}"

        // Scope reads as the single site, the whole campground, or a plural
        // count — in that order of specificity.
        val (fieldLabel, fieldValue) =
            when {
                notice.siteName != null ->
                    "Site" to "*${notice.siteName}*${notice.siteLoop?.let { " ($it)" }.orEmpty()}"
                notice.campgroundName != null ->
                    "Campground" to "*${notice.campgroundName}*"
                else ->
                    "Sites" to "${notice.siteCount}"
            }

        val blocks =
            buildList {
                add(SlackBlocks.header(headerFor(notice.state)))
                add(
                    SlackBlocks.fields(
                        listOf(
                            // Clamp the whole field (label + value): Slack's limit is on
                            // the field's text object, not just the value.
                            truncate("*$fieldLabel*\n$fieldValue", FIELD_TEXT_MAX),
                            "*Window*\n$window",
                        ),
                    ),
                )
                add(SlackBlocks.section(truncate(statusLine(notice.state), SECTION_TEXT_MAX)))
                addAll(linkButtons(notice))
            }

        return fallback(notice, scopePlain(notice), window) to blocks
    }

    /** Big plain-text card title. Emoji leads so the message reads at a glance. */
    private fun headerFor(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING, WatchStatusNotice.State.UNCHECKED -> "👀 Watching for openings"
            WatchStatusNotice.State.PAUSED -> "⏸️ Watch paused"
            WatchStatusNotice.State.DONE -> "✅ Watch complete"
            WatchStatusNotice.State.STOPPED -> "🛑 Watch stopped"
        }

    /** The one-line status sentence under the fields. */
    private fun statusLine(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING -> "Nothing available right now — I'll alert the moment a site opens."
            WatchStatusNotice.State.UNCHECKED -> "Availability not checked yet — I'll alert the moment a site opens."
            WatchStatusNotice.State.PAUSED -> "Paused — I won't alert until this watch is resumed."
            WatchStatusNotice.State.DONE -> "This watch is complete — no more alerts."
            WatchStatusNotice.State.STOPPED -> "Deleted — I've stopped watching and won't alert again."
        }

    /** The deep-links as actions blocks of link buttons, or empty when the watch
     *  carries none (both hosts unconfigured, or no POI-scoped targets). One
     *  button for the watch dashboard, then a map + grid button per watched POI.
     *  A single POI reads plainly ("view on map", "availability grid"); several
     *  are suffixed with the POI id so each button is distinguishable. Buttons
     *  are chunked to Slack's per-block cap so a many-POI watch can't be rejected.
     *  The `url` binding lives here, never in the domain [WatchStatusNotice]. */
    private fun linkButtons(notice: WatchStatusNotice): List<SlackBlockDto> {
        val single = notice.poiLinks.size == 1
        val buttons =
            buildList {
                notice.dashboardUrl?.let { add(ButtonSpec(truncate("📊 watch dashboard", BUTTON_TEXT_MAX), it)) }
                notice.poiLinks.forEach { poi ->
                    val suffix = if (single) "" else " ${poi.poiId}"
                    poi.mapUrl?.let { add(ButtonSpec(truncate("🗺 view on map$suffix", BUTTON_TEXT_MAX), it)) }
                    poi.gridUrl?.let { add(ButtonSpec(truncate("🗓 availability grid$suffix", BUTTON_TEXT_MAX), it)) }
                }
            }
        return buttons.chunked(SlackBlocks.ACTIONS_MAX_ELEMENTS).map(SlackBlocks::actions)
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
            WatchStatusNotice.State.STOPPED ->
                "🛑 Stopped watching $scope for $window — the watch was deleted."
        }

    /** Unformatted scope for the fallback line: the single site's name (+ loop),
     *  the whole campground's name, or the plural count. */
    private fun scopePlain(notice: WatchStatusNotice): String =
        when {
            notice.siteName != null -> "${notice.siteName}${notice.siteLoop?.let { " ($it)" }.orEmpty()}"
            notice.campgroundName != null -> notice.campgroundName
            else -> "${notice.siteCount} sites"
        }

    /** Clamps [s] to [max] chars, replacing the tail with an ellipsis so the
     *  result is never longer than [max]. */
    private fun truncate(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"
}
