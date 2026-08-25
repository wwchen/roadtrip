package ca.floo.roadtrip.service.notification.email

import ca.floo.roadtrip.model.api.magicLinkStopUrl
import ca.floo.roadtrip.model.api.watchModifyUrl

private const val MANAGE_LABEL = "Manage watch"
private const val STOP_LABEL = "Stop watch"

/**
 * The control links every watch email offers, in one place because both
 * renderers emit the same pair and had drifted on how they built the fallback.
 *
 * Manage always: the magic link when there is one, else the session-gated page.
 * Stop only with a magic link — without a token the page could not carry out the
 * stop the link offers.
 */
internal fun watchControlLinks(
    appRootUrl: String?,
    watchId: Long,
    magicLinkUrl: String?,
): List<EmailLink> =
    buildList {
        val sessionUrl = appRootUrl?.let { watchModifyUrl(it, watchId) }
        (magicLinkUrl ?: sessionUrl)?.let { add(EmailLink(MANAGE_LABEL, it)) }
        magicLinkUrl?.let { add(EmailLink(STOP_LABEL, magicLinkStopUrl(it))) }
    }
