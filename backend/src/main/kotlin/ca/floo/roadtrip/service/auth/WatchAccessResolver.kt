package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UserRepo

/**
 * Session first, then the link token, so an owner on a signed-in browser keeps
 * the full grant and someone signed in as another account still falls through to
 * the link. [Resolution.NotFound] conflates "no such watch" with "not yours".
 */
internal class WatchAccessResolver(
    private val watchRepo: AvailabilityWatchRepo,
    private val userRepo: UserRepo,
) {
    sealed interface Resolution {
        data class Granted(
            val watch: AvailabilityWatchRepo.Watch,
            /** Gates delivery config, which carries the owner's Slack channel. */
            val viaMagicLink: Boolean,
        ) : Resolution

        /** `401`. */
        data object Unauthenticated : Resolution

        /** `404`. */
        data object NotFound : Resolution
    }

    fun resolve(
        principal: Principal,
        watchId: Long,
        magicLinkToken: String?,
    ): Resolution {
        val ownerAccess = ownerAccess(principal, watchId)
        if (ownerAccess != null) return ownerAccess

        // One answer for every way a link can fail — absent, forged, for another
        // watch, or pointing at one that has been stopped. Sign-in is the only
        // remedy we can offer an anonymous caller; a signed-in one has already
        // failed the ownership check above, so for them it is a 404.
        val watch = magicLinkToken?.let { watchRepo.findByIdMatchingMagicLinkToken(watchId, it) }
        if (watch == null) {
            return if (principal is Principal.User) Resolution.NotFound else Resolution.Unauthenticated
        }
        return Resolution.Granted(watch, viaMagicLink = true)
    }

    /**
     * The account behind a session principal. The session was live when the
     * route resolved it, so a missing row is a data bug, not an authz outcome.
     */
    fun account(principal: Principal.User): User =
        requireNotNull(userRepo.findById(principal.userId)) {
            "no app_user for authenticated principal ${principal.userId}"
        }

    /** Null falls through to the token, so a forwarded link still works. */
    private fun ownerAccess(
        principal: Principal,
        watchId: Long,
    ): Resolution.Granted? {
        val user = (principal as? Principal.User)?.let(::account) ?: return null
        val watch = watchRepo.findById(watchId) ?: return null
        if (!user.isAdmin && watch.ownerUserId != user.id.value) return null
        return Resolution.Granted(watch, viaMagicLink = false)
    }
}
