package ca.floo.roadtrip.service.notification

import java.time.LocalDate

/**
 * A watch's current lifecycle/status, handed to
 * [SlackNotificationService.sendWatchStatus] to render as a Slack status card.
 * Unlike [WatchOpening] (a real opening), this carries no availability — it
 * tells the user the watch is live, paused, or done. Every field is plain
 * domain data, including the dashboard deep-link URLs: the notification layer
 * owns turning them into Block Kit and never reaches back into the availability
 * domain, and this type never surfaces Slack's `<url|label>` markup.
 *
 * Scope is one of: a single named site ([siteName] set), a whole campground
 * ([campgroundName] set — the watch covers every site in one POI), or a plural
 * count ([siteName] and [campgroundName] null); the renderer labels and formats
 * accordingly.
 */
data class WatchStatusNotice(
    val state: State,
    val siteCount: Int,
    val siteName: String?,
    val siteLoop: String?,
    val campgroundName: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val dashboardUrl: String?,
    val poiLinks: List<PoiLink>,
) {
    /** Which status card to render. [WATCHING] and [UNCHECKED] are both live
     *  (actively watching); they differ only in whether the cube has an
     *  observation for the window yet. [STOPPED] is a watch the user deleted —
     *  a terminal goodbye card, sent once just before the row is removed. */
    enum class State { PAUSED, DONE, WATCHING, UNCHECKED, STOPPED }

    /** Deep links for one watched POI: its page on the web map ([mapUrl]) and
     *  its Grafana availability grid ([gridUrl]). Either may be null when the
     *  corresponding host is unconfigured; a POI with both null contributes no
     *  links. */
    data class PoiLink(
        val poiId: Long,
        val mapUrl: String?,
        val gridUrl: String?,
    )
}
