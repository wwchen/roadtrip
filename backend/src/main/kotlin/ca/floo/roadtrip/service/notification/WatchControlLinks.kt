package ca.floo.roadtrip.service.notification

/**
 * Deep-links to the web app's alerts panel for changing a watch straight from a
 * Slack card: pause / resume (whichever the watch's current state allows) and
 * delete. The caller (the availability dispatcher) builds each URL from the
 * app root and the watch id; a field is null when the host is unconfigured or
 * the action doesn't apply in the watch's current state, and a null field
 * drops that control.
 *
 * Every field is plain domain data (a URL string or null); the notification
 * layer owns turning them into Block Kit `<url|label>` links (see
 * [WatchControlLinksRenderer]) and this type never surfaces Slack markup. The
 * URLs open the app's alerts panel focused on the watch, where the existing
 * controls perform the PATCH/DELETE — a Slack card never mutates a watch
 * itself, matching this app's outbound-only Slack posture (see
 * [ca.floo.roadtrip.clients.slack.LinkSpec]).
 */
data class WatchControlLinks(
    val pauseUrl: String? = null,
    val resumeUrl: String? = null,
    val deleteUrl: String? = null,
) {
    /** True when no control applies — the renderer emits no control section. */
    val isEmpty: Boolean get() = pauseUrl == null && resumeUrl == null && deleteUrl == null
}
