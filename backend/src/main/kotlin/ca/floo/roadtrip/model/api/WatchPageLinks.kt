package ca.floo.roadtrip.model.api

/**
 * Every deep link into the watches page, in one file: notifications build them,
 * the route and the frontend parse them, and the magic-link URLs sit in
 * mailboxes forever — so the shape is a published contract with exactly one
 * definition.
 *
 * The token constant is named for the column it carries,
 * `availability_watch.magic_link_token`; the value is short because it rides in
 * a URL that people see.
 */
const val MAGIC_LINK_TOKEN_PARAM = "t"

const val MAGIC_LINK_WATCH_PARAM = "watch"

/** Tells the watches page to stop the watch on arrival. */
const val MAGIC_LINK_ACTION_PARAM = "action"
const val MAGIC_LINK_STOP_ACTION = "stop"

private const val WATCHES_PATH = "/watches"

/** The session-gated editor for one watch — the pre-magic-link deep link. */
fun watchModifyUrl(
    appRootUrl: String,
    watchId: Long,
): String = "${appRootUrl.trimEnd('/')}$WATCHES_PATH?action=modify&id=$watchId"

/** The magic link: manages [watchId] with no session, bearing [token]. */
fun magicLinkUrl(
    appRootUrl: String,
    watchId: Long,
    token: String,
): String = "${appRootUrl.trimEnd('/')}$WATCHES_PATH?$MAGIC_LINK_WATCH_PARAM=$watchId&$MAGIC_LINK_TOKEN_PARAM=$token"

/**
 * The same link, asking the page to stop the watch as it loads.
 *
 * The mutation is the page's POST, not this GET: mail clients and scanners
 * prefetch links, and a URL that stopped a watch by being fetched would fire for
 * anyone whose provider does that.
 */
fun magicLinkStopUrl(magicLinkUrl: String): String = "$magicLinkUrl&$MAGIC_LINK_ACTION_PARAM=$MAGIC_LINK_STOP_ACTION"
