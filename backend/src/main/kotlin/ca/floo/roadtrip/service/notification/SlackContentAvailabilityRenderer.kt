package ca.floo.roadtrip.service.notification

import ca.floo.roadtrip.clients.slack.SlackBlockDto
import ca.floo.roadtrip.clients.slack.SlackBlocks
import java.time.LocalDate

/**
 * Maps availability [WatchOpening]s to the Slack "Campsites Available!" alert —
 * a notification-fallback string plus the Block Kit body (header, campground /
 * count / window fields, per-site lines, and a Reserve link). This is the one
 * place that turns the domain DTO into Slack content, so the notification
 * service owns the mapping and the dispatcher only supplies data.
 *
 * All rendered text is clamped to Slack's per-element limits ([RESERVE_LABEL_MAX],
 * [SECTION_TEXT_MAX], [FIELD_TEXT_MAX]); an over-long site name would otherwise
 * make `chat.postMessage` reject the whole message (`invalid_blocks`) and the
 * alert would silently never arrive.
 */
object SlackContentAvailabilityRenderer {
    /** At most this many site lines are listed; the rest are summarized as "…and N more". */
    const val MAX_SITES_IN_MESSAGE = 10

    // Slack Block Kit hard limits (chars). Exceeding any of these fails the post.
    private const val SECTION_TEXT_MAX = 3000
    private const val FIELD_TEXT_MAX = 2000

    /** The Reserve link label is kept short for readability, not a Slack limit. */
    private const val RESERVE_LABEL_MAX = 75

    private const val RESERVE_PREFIX = "Reserve "
    private const val RESERVE_SUFFIX = " →"

    /**
     * Renders the openings alert for a watch window. Returns the fallback text
     * (shown in notifications / by clients that don't render blocks) paired with
     * the Block Kit body. [openings] must be non-empty — the alert exists because
     * something opened.
     */
    fun openings(
        startDate: LocalDate,
        endDate: LocalDate,
        openings: List<WatchOpening>,
    ): Pair<String, List<SlackBlockDto>> {
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
                "• $prefix*${row.label}*$loop — ${row.date}$type"
            }
        val more = if (count > MAX_SITES_IN_MESSAGE) "\n…and ${count - MAX_SITES_IN_MESSAGE} more" else ""
        val sitesFound = "$count${if (count > MAX_SITES_IN_MESSAGE) " (showing $MAX_SITES_IN_MESSAGE)" else ""}"

        // A single Reserve CTA is only meaningful when every opening is in one
        // campground; across parks the "first" site would be an arbitrary pick,
        // so the link is dropped and the per-site lines carry the detail.
        val first = rows.first()
        val reserveLink =
            if (!multiCampground) {
                first.bookingUrl?.let { url ->
                    val labelBudget = RESERVE_LABEL_MAX - RESERVE_PREFIX.length - RESERVE_SUFFIX.length
                    SlackBlocks.primaryLink("$RESERVE_PREFIX${truncate(first.label, labelBudget)}$RESERVE_SUFFIX", url)
                }
            } else {
                null
            }

        val blocks =
            listOfNotNull(
                SlackBlocks.header("🏕️ Campsites Available!"),
                SlackBlocks.fields(
                    listOf(
                        "*Campground*\n${truncate(campgroundLabel, FIELD_TEXT_MAX)}",
                        "*Sites found*\n$sitesFound",
                        "*Your window*\n$startDate → $endDate",
                    ),
                ),
                SlackBlocks.section(truncate("$siteLines$more", SECTION_TEXT_MAX)),
                reserveLink,
            )

        val plural = if (count == 1) "" else "s"
        val where = if (!multiCampground && campgroundNames.size == 1) " at ${campgroundNames.single()}" else ""
        return "⛺ $count campsite$plural available$where" to blocks
    }

    /** Clamps [s] to [max] chars, replacing the tail with an ellipsis so the
     *  result is never longer than [max]. */
    private fun truncate(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"
}
