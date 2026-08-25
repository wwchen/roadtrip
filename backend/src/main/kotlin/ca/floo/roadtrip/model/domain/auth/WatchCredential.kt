package ca.floo.roadtrip.model.domain.auth

/**
 * How a caller proved they may act on **one** watch.
 *
 * [Principal] answers "who is this request", which is the right question for a
 * surface scoped to a user (list my watches, create a watch). It is the wrong
 * question for the alert-email magic link, where there is no user at all — only
 * a bearer of a secret that names a single watch. Modelling that as a second
 * kind of credential, rather than as a synthetic [Principal], keeps the blast
 * radius visible in the type: a [MagicLink] carries a watch id and therefore
 * cannot be mistaken for an identity that could reach anything else.
 *
 * Enforcement lives in the watch controller: [Session] falls through to the
 * existing owner/admin check, [MagicLink] authorizes exactly [MagicLink.watchId].
 */
sealed interface WatchCredential {
    /** A signed-in user, acting through the app. Ownership still applies. */
    data class Session(
        val principal: Principal.User,
    ) : WatchCredential

    /**
     * A live capability token from an alert email. Authorizes read, modify, and
     * stop on [watchId] and nothing else — not listing, not creating, not any
     * other watch, not the owner's account.
     */
    data class MagicLink(
        val watchId: Long,
    ) : WatchCredential
}
