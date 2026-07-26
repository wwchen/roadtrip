package ca.floo.roadtrip.model.domain.auth

/**
 * Who a unit of work is running as. Services take a [Principal]; Ktor types stay
 * in `route/`, so nothing below the HTTP shell knows how the caller was
 * identified.
 *
 * Nothing resolves a [Principal] from a request yet — that arrives with the auth
 * routes (PR 3) and becomes ambient in the authz pass. The type lands here so
 * service signatures can be written against it as they are touched.
 */
sealed interface Principal {
    /** No session presented. Browsing is anonymous by design. */
    data object Anonymous : Principal

    /** A signed-in human. [roles] is a snapshot resolved with the session. */
    data class User(
        val userId: UserId,
        val roles: Set<Role> = emptySet(),
    ) : Principal

    /**
     * Internal work with no human behind it: schedulers, ETL, the availability
     * poller. Never constructed from a request — a route that produced this
     * would be handing out unrestricted access.
     */
    data object System : Principal
}

/** The signed-in user's id, or null for [Principal.Anonymous] and [Principal.System]. */
fun Principal.userIdOrNull(): UserId? = (this as? Principal.User)?.userId

/** True when this principal holds [role]. [Principal.System] holds every role. */
fun Principal.hasRole(role: Role): Boolean =
    when (this) {
        is Principal.System -> true
        is Principal.User -> role in roles
        is Principal.Anonymous -> false
    }
