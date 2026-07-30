package ca.floo.roadtrip.service.notification.common

import java.time.LocalDate

/**
 * A watch's current lifecycle/status, handed to
 * [NotificationSender.sendWatchStatus] to render as a transport-specific
 * status message.
 * Unlike [WatchOpening] (a real opening), this carries no availability — it
 * tells the user the watch is live, paused, or done. Every field is plain
 * domain data, including any per-POI deep links: the notification layer owns
 * turning them into Block Kit buttons and never reaches back into the
 * availability domain.
 *
 * [watchId] is echoed into every interactive button's `value` so the Slack
 * interactivity handler (see
 * [ca.floo.roadtrip.route.api.slack.slackInteractivityRoute]) can identify the target
 * watch on pause / resume / delete without trusting the client for a routing
 * key. Scope is one of: a single named site ([siteName] set), a whole
 * campground ([campgroundName] set — the watch covers every site in one POI),
 * or a plural count ([siteName] and [campgroundName] null); the renderer
 * labels and formats accordingly.
 *
 * The applicable controls come from [state] alone — an active watch pauses, a
 * paused one resumes, a done one only deletes — so callers no longer pass a
 * separate control-links object.
 */
data class WatchStatusNotice(
    val watchId: Long,
    val state: State,
    val siteCount: Int,
    val siteName: String?,
    val siteLoop: String?,
    val campgroundName: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val poiLinks: List<PoiLink>,
    val appRootUrl: String? = null,
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
