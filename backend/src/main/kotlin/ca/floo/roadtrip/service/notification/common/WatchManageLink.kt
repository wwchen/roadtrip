package ca.floo.roadtrip.service.notification.common

private const val WATCHES_PATH = "/watches"
private const val ACTION_PARAM = "action"
private const val MODIFY_ACTION = "modify"
private const val ID_PARAM = "id"

/**
 * The query parameter an alert email's magic link carries. The watches page
 * reads it, uses it for that page's API calls, and strips it from the address
 * bar; the API accepts it on the single-watch routes. Named in one place because
 * three layers have to agree on it — email, web app, and route.
 */
const val WATCH_TOKEN_PARAM = "watch_token"

/**
 * What the magic link is called wherever it is rendered. It says "stop" because
 * stopping is the thing a reader most often wants from an alert, and the reason
 * the link stopped requiring a session; the page it opens does both.
 */
const val WATCH_MANAGE_LABEL = "Manage or stop this alert"

/**
 * Builds the "manage this watch" deep link that notifications point at.
 *
 * One place for the shape because four renderers emit it (opening alerts and
 * lifecycle notices, over email and Slack) and the watches page parses it. When
 * [token] is present the link is a magic link: it works from a mailbox with no
 * session, which is the whole point of mailing it. Without one it is the old
 * link, which lands on a sign-in prompt for a signed-out reader.
 *
 * Returns null when the web app's root URL is unconfigured — a first-class "no
 * deep links" state, matching how POI links already behave.
 */
object WatchManageLink {
    fun url(
        appRootUrl: String?,
        watchId: Long,
        token: String? = null,
    ): String? {
        val root = appRootUrl?.trimEnd('/') ?: return null
        val query =
            buildString {
                append("$ACTION_PARAM=$MODIFY_ACTION&$ID_PARAM=$watchId")
                token?.let { append("&$WATCH_TOKEN_PARAM=$it") }
            }
        return "$root$WATCHES_PATH?$query"
    }
}
