package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackBlocks
import ca.floo.roadtrip.clients.slack.SlackButtonSpec
import ca.floo.roadtrip.clients.slack.SlackConfirmSpec
import ca.floo.roadtrip.service.notification.common.WatchOpening
import java.time.LocalDate

/**
 * Maps availability [WatchOpening]s to the Slack "Sites available" alert — a
 * notification-fallback string plus the attachment-wrapped Block Kit body
 * (green `--rt-avail` color bar, header, campground / window fields, up to
 * [MAX_SITES_IN_MESSAGE] site lines, a Reserve primary URL button + Grid URL
 * button + Pause / Delete interactive buttons, and a context sub-line). This
 * is the one place that turns the domain DTO into Slack content, so the
 * notification service owns the mapping and the dispatcher only supplies data.
 *
 * All rendered text is clamped to Slack's per-element limits ([RESERVE_LABEL_MAX],
 * [SECTION_TEXT_MAX], [FIELD_TEXT_MAX]); an over-long site name would otherwise
 * make `chat.postMessage` reject the whole message (`invalid_blocks`) and the
 * alert would silently never arrive.
 */
object SlackContentAvailabilityRenderer {
    /** At most this many site lines are listed; the rest are summarized as
     *  "…and N more". Kept small — a Slack card is a glance surface, not a
     *  full report; the user opens the grid / campground page for the full list. */
    const val MAX_SITES_IN_MESSAGE = 3

    // Slack Block Kit hard limits (chars). Exceeding any of these fails the post.
    private const val SECTION_TEXT_MAX = 3000
    private const val FIELD_TEXT_MAX = 2000

    /** The Reserve button label is kept short for readability, not a Slack limit. */
    private const val RESERVE_LABEL_MAX = 75

    private const val RESERVE_PREFIX = "🎟️ Reserve "

    /**
     * Renders the openings alert for a watch window. Returns the fallback text
     * (shown in notifications and by clients that don't render blocks) paired
     * with the attachment-wrapped card. [openings] must be non-empty — the
     * alert exists because something opened. [watchId] identifies the target
     * watch on any interactive-button callback (pause / delete).
     */
    fun openings(
        watchId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
        appRootUrl: String? = null,
    ): Pair<String, List<SlackAttachmentDto>> {
        require(openings.isNotEmpty()) { "openings alert requires at least one opening" }
        val rows = openings.sortedWith(compareBy({ it.campgroundId ?: Long.MAX_VALUE }, { it.label }, { it.date }))
        val count = rows.size

        // Distinct campgrounds are counted by parent POI id, not name, so parks
        // with an un-hydrated (null) name are not silently collapsed into one.
        val campgroundIds = rows.mapNotNull { it.campgroundId }.distinct()
        val campgroundNames = rows.mapNotNull { it.campground }.distinct()
        val multiCampground = campgroundIds.size > 1
        val campgroundLabel =
            when {
                multiCampground -> "${campgroundIds.size} campgrounds"
                campgroundNames.size == 1 -> campgroundNames.single()
                else -> "—"
            }

        val siteLines =
            rows.take(MAX_SITES_IN_MESSAGE).joinToString("\n") { row ->
                val prefix = if (multiCampground && row.campground != null) "${row.campground} — " else ""
                val loop = row.loop?.let { " ($it)" }.orEmpty()
                val type = row.siteType?.let { " _($it)_" }.orEmpty()
                "🟢 $prefix*${row.label}*$loop — ${row.date}$type"
            }
        val more = if (count > MAX_SITES_IN_MESSAGE) "\n_+ ${count - MAX_SITES_IN_MESSAGE} more_" else ""

        // A single Reserve CTA is only meaningful when every opening is in one
        // campground; across parks the "first" site would be an arbitrary pick,
        // so the button is dropped and the per-site lines carry the detail.
        val first = rows.first()
        val reserveButton: SlackButtonSpec? =
            if (!multiCampground) {
                first.bookingUrl?.let { url ->
                    val labelBudget = RESERVE_LABEL_MAX - RESERVE_PREFIX.length
                    SlackButtonSpec(
                        label = "$RESERVE_PREFIX${truncate(first.label, labelBudget)}",
                        actionId = SlackWatchCard.ACTION_RESERVE_SITE,
                        url = url,
                        value = watchId.toString(),
                        style = SlackButtonSpec.Style.PRIMARY,
                    )
                }
            } else {
                null
            }

        // Grid link points at the app's alerts panel focused on this watch —
        // matches the existing deep-link scheme (?alert=<id>) so the user
        // lands on the exact heatmap for the openings they just saw.
        val gridButton: SlackButtonSpec? =
            appRootUrl?.let { root ->
                SlackButtonSpec(
                    label = "Availability grid",
                    actionId = SlackWatchCard.ACTION_OPEN_GRID,
                    url = "$root/?alert=$watchId",
                    value = watchId.toString(),
                )
            }

        val buttons =
            buildList {
                reserveButton?.let { add(it) }
                gridButton?.let { add(it) }
                add(
                    SlackButtonSpec(
                        label = "⏸ Pause",
                        actionId = SlackWatchCard.ACTION_WATCH_PAUSE,
                        value = watchId.toString(),
                    ),
                )
                add(deleteButton(watchId, "these alerts"))
            }

        val blocks =
            listOfNotNull(
                SlackBlocks.section(text = "*🏕️ ${sitesOpenedHeadline(count)}*"),
                SlackBlocks.fields(
                    listOf(
                        "*Campground*\n${truncate(campgroundLabel, FIELD_TEXT_MAX)}",
                        "*Your window*\n`$startDate → $endDate`",
                    ),
                ),
                SlackBlocks.section(truncate("$siteLines$more", SECTION_TEXT_MAX)),
                SlackBlocks.actions(buttons),
                SlackBlocks.context("Checked just now  ·  Reserve links straight to Recreation.gov"),
            )

        val attachment = SlackAttachmentDto(color = SlackWatchCard.COLOR_AVAIL, blocks = blocks)
        val plural = if (count == 1) "site" else "sites"
        val where = if (!multiCampground && campgroundNames.size == 1) " at ${campgroundNames.single()}" else ""
        val fallback = "🏕️ $count $plural just opened$where ($startDate → $endDate)"
        return fallback to listOf(attachment)
    }

    private fun sitesOpenedHeadline(count: Int): String = if (count == 1) "1 site just opened" else "$count sites just opened"

    /** Danger-styled Delete button with Slack's native confirm dialog so a stray
     *  tap doesn't silently kill the watch. [subject] fills the "you'll stop
     *  getting …" line so the same helper suits both alert cards and status
     *  cards. */
    internal fun deleteButton(
        watchId: Long,
        subject: String,
    ): SlackButtonSpec =
        SlackButtonSpec(
            label = "🗑 Delete",
            actionId = SlackWatchCard.ACTION_WATCH_DELETE,
            value = watchId.toString(),
            style = SlackButtonSpec.Style.DANGER,
            confirm =
                SlackConfirmSpec(
                    title = "Delete this watch?",
                    text = "You'll stop getting $subject.",
                    confirm = "Delete",
                    deny = "Keep",
                    danger = true,
                ),
        )

    /** Clamps [s] to [max] chars, replacing the tail with an ellipsis so the
     *  result is never longer than [max]. */
    private fun truncate(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"
}
