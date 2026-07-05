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
 * Scope is either a single named site ([siteName] set, [siteCount] == 1) or a
 * plural count ([siteName] null); the renderer labels and formats accordingly.
 */
data class WatchStatusNotice(
    val state: State,
    val siteCount: Int,
    val siteName: String?,
    val siteLoop: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val dashboardUrl: String?,
    val poiLinks: List<PoiLink>,
) {
    /** Which status card to render. [WATCHING] and [UNCHECKED] are both live
     *  (actively watching); they differ only in whether the cube has an
     *  observation for the window yet. */
    enum class State { PAUSED, DONE, WATCHING, UNCHECKED }

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
