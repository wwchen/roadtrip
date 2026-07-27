package ca.floo.roadtrip.model.domain.auth

/**
 * The access level a route declares for itself. Every route must declare one —
 * an undeclared route fails the build (see the coverage check in
 * `route/common`), rather than defaulting to allow or deny. See RFC 0010.
 *
 * This is a domain value, not a Ktor concept: the route layer attaches it to a
 * route and enforces it, but the shape and the enforcement policy ([check]) know
 * nothing about HTTP.
 */
sealed interface RouteAccess {
    /** No principal required. The caller may still be signed in. */
    data object Anonymous : RouteAccess

    /** Any signed-in, active user. */
    data object User : RouteAccess

    /** A signed-in user holding [role]. */
    data class HasRole(
        val role: Role,
    ) : RouteAccess

    /**
     * Authenticated by request signature, not by session — the Slack webhook.
     * A distinct level so the coverage check cannot be satisfied by pretending a
     * signed webhook is anonymous: it *is* authenticated, just not by us, so the
     * session gate does not apply.
     */
    data object Signed : RouteAccess
}

/** The outcome of checking a [Principal] against a [RouteAccess] level. */
sealed interface AccessCheck {
    data object Allow : AccessCheck

    /** No principal where one is required — the route layer answers `401`. */
    data object Unauthenticated : AccessCheck

    /** A principal that lacks the required role — the route layer answers `403`. */
    data object Forbidden : AccessCheck
}

/**
 * Decides whether [principal] may reach a route declared at this access level.
 *
 * [Principal.System] holds every role (see [hasRole]), so internal work is never
 * refused. [RouteAccess.Anonymous] and [RouteAccess.Signed] never gate on the
 * session principal — `Anonymous` by definition, `Signed` because the route
 * proves the caller by signature itself.
 */
fun RouteAccess.check(principal: Principal): AccessCheck =
    when (this) {
        RouteAccess.Anonymous, RouteAccess.Signed -> AccessCheck.Allow
        RouteAccess.User ->
            when (principal) {
                Principal.Anonymous -> AccessCheck.Unauthenticated
                is Principal.User, Principal.System -> AccessCheck.Allow
            }
        is RouteAccess.HasRole ->
            when {
                principal.hasRole(role) -> AccessCheck.Allow
                principal is Principal.Anonymous -> AccessCheck.Unauthenticated
                else -> AccessCheck.Forbidden
            }
    }
