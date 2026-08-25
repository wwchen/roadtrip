package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.client.slack.SlackAttachmentDto
import ca.floo.roadtrip.client.slack.SlackBlocks
import ca.floo.roadtrip.client.slack.SlackButtonSpec
import ca.floo.roadtrip.model.api.watchModifyUrl
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice

/**
 * Maps a [WatchStatusNotice] to the Slack watch-status card — a notification
 * fallback string plus the attachment-wrapped Block Kit body: a color-bar
 * accent keyed to the state (blue while watching, gray while paused, green on
 * done, gray on stopped), a headline, scope / window fields, a one-line
 * status, an actions row of buttons (whichever pause / resume / delete apply,
 * plus map / grid deep-links per POI), and a context sub-line.
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
     * clients that don't render blocks) paired with the attachment-wrapped
     * card.
     */
    fun render(notice: WatchStatusNotice): Pair<String, List<SlackAttachmentDto>> {
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
                add(SlackBlocks.section("*${headerFor(notice.state)}*"))
                add(
                    SlackBlocks.fields(
                        listOf(
                            truncate("*$fieldLabel*\n$fieldValue", FIELD_TEXT_MAX),
                            "*Window*\n`$window`",
                        ),
                    ),
                )
                add(SlackBlocks.section(truncate(statusLine(notice.state), SECTION_TEXT_MAX)))
                buttons(notice).takeIf { it.isNotEmpty() }?.let { add(SlackBlocks.actions(it)) }
                add(SlackBlocks.context(contextLine(notice.state)))
            }

        val attachment = SlackAttachmentDto(color = colorFor(notice.state), blocks = blocks)
        return fallback(notice, scopePlain(notice), window) to listOf(attachment)
    }

    /** Big headline. Emoji leads so the message reads at a glance. */
    private fun headerFor(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING, WatchStatusNotice.State.UNCHECKED -> "👀 Watching for openings"
            WatchStatusNotice.State.PAUSED -> "⏸ Watch paused"
            WatchStatusNotice.State.DONE -> "✅ Watch complete"
            WatchStatusNotice.State.STOPPED -> "🛑 Watch stopped"
        }

    /** The color bar keyed to state per the design system. WATCHING is the
     *  interactive-blue (`--rt-brand`), DONE the availability-green, PAUSED /
     *  STOPPED the neutral gray, matching the spec's five-state palette. */
    private fun colorFor(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING, WatchStatusNotice.State.UNCHECKED -> SlackWatchCard.COLOR_WATCHING
            WatchStatusNotice.State.PAUSED, WatchStatusNotice.State.STOPPED -> SlackWatchCard.COLOR_MUTED
            WatchStatusNotice.State.DONE -> SlackWatchCard.COLOR_AVAIL
        }

    /** The one-line status sentence under the fields. */
    private fun statusLine(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING -> "Nothing open right now — I'll ping you the moment a site frees up."
            WatchStatusNotice.State.UNCHECKED -> "Availability not checked yet — I'll ping you the moment a site opens."
            WatchStatusNotice.State.PAUSED -> "Paused — I won't alert until you resume this watch."
            WatchStatusNotice.State.DONE -> "This watch is complete — no more alerts."
            WatchStatusNotice.State.STOPPED -> "Deleted — I've stopped watching and won't alert again."
        }

    /** The muted sub-line under the actions row. Kept generic (no exact
     *  timestamp) since the renderer doesn't know when the poller last ran. */
    private fun contextLine(state: WatchStatusNotice.State): String =
        when (state) {
            WatchStatusNotice.State.WATCHING -> "Armed · checking on the watch's cadence"
            WatchStatusNotice.State.UNCHECKED -> "Armed · first check pending"
            WatchStatusNotice.State.PAUSED -> "No further alerts until this watch is resumed"
            WatchStatusNotice.State.DONE -> "This watch fired and stopped itself"
            WatchStatusNotice.State.STOPPED -> "This watch was deleted"
        }

    /** The applicable action buttons for the state. Live watches offer Pause +
     *  Modify + Delete; paused ones offer Resume + Modify + Delete; done watches
     *  offer Delete; a just-stopped card is terminal (no buttons). Grid / map
     *  deep-links per POI slot in ahead of the mutation buttons when their host
     *  is configured. */
    private fun buttons(notice: WatchStatusNotice): List<SlackButtonSpec> {
        if (notice.state == WatchStatusNotice.State.STOPPED) return emptyList()
        val out = mutableListOf<SlackButtonSpec>()
        val stateButton =
            when (notice.state) {
                WatchStatusNotice.State.WATCHING, WatchStatusNotice.State.UNCHECKED ->
                    SlackButtonSpec(
                        label = "⏸ Pause",
                        actionId = SlackWatchCard.ACTION_WATCH_PAUSE,
                        value = notice.watchId.toString(),
                    )
                WatchStatusNotice.State.PAUSED ->
                    SlackButtonSpec(
                        label = "▶ Resume",
                        actionId = SlackWatchCard.ACTION_WATCH_RESUME,
                        value = notice.watchId.toString(),
                        style = SlackButtonSpec.Style.PRIMARY,
                    )
                WatchStatusNotice.State.DONE, WatchStatusNotice.State.STOPPED -> null
            }
        // Deep-links (URL buttons). Slack caps an actions row at 25 buttons —
        // we're always well under, but multi-POI watches stay ordered by id
        // so the row is deterministic across renders.
        notice.poiLinks.forEach { poi ->
            poi.gridUrl?.let {
                out +=
                    SlackButtonSpec(
                        label = if (notice.poiLinks.size > 1) "Grid ${poi.poiId}" else "Availability grid",
                        actionId = SlackWatchCard.ACTION_OPEN_GRID,
                        url = it,
                        value = notice.watchId.toString(),
                    )
            }
            poi.mapUrl?.let {
                out +=
                    SlackButtonSpec(
                        label = if (notice.poiLinks.size > 1) "Map ${poi.poiId}" else "View on map",
                        actionId = SlackWatchCard.ACTION_OPEN_MAP,
                        url = it,
                        value = notice.watchId.toString(),
                    )
            }
        }
        // Modify button — links to the /watches page with the watch pre-loaded
        // for editing. Available on all live states (not DONE/STOPPED).
        if (notice.state != WatchStatusNotice.State.DONE) {
            notice.appRootUrl?.let { root ->
                out +=
                    SlackButtonSpec(
                        label = "✏️ Modify",
                        actionId = SlackWatchCard.ACTION_OPEN_WATCHES,
                        url = watchModifyUrl(root, notice.watchId),
                        value = notice.watchId.toString(),
                    )
            }
        }
        // Match the openings alert's muscle memory: navigation first, the
        // Pause/Resume state action next, and Delete last. That keeps the
        // state-toggle button from jumping to the far left after Slack edits.
        stateButton?.let { out += it }
        // Delete is always available (except on the terminal STOPPED card,
        // handled above) — it's the escape hatch and stays as the danger CTA.
        out += SlackContentAvailabilityRenderer.deleteButton(notice.watchId, deleteSubject(notice))
        return out
    }

    private fun deleteSubject(notice: WatchStatusNotice): String =
        when {
            notice.siteName != null -> notice.siteName
            notice.campgroundName != null -> notice.campgroundName
            else -> "these alerts"
        }

    /** Notification-fallback line — reads as a full sentence wherever blocks
     *  aren't rendered. */
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
                "⏸ Paused watching $scope for $window — I won't alert until it's resumed."
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
