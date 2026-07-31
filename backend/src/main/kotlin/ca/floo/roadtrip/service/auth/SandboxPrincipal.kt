package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId

/** Session-cookie sentinel the sandbox switcher sets: "sandbox:<userId>". */
const val SANDBOX_TOKEN_PREFIX = "sandbox:"

/**
 * Maps a sandbox sentinel token to a [Principal.User], or [Principal.Anonymous]
 * for anything unexpected. Only ever constructs [Principal.User] — never
 * [Principal.System]. Callers MUST only invoke this when auth is disabled.
 *
 * @param loadUser returns the user's roles, or null if the user does not exist.
 */
fun sandboxPrincipal(
    token: String?,
    loadUser: (UserId) -> Set<Role>?,
): Principal {
    if (token == null || !token.startsWith(SANDBOX_TOKEN_PREFIX)) return Principal.Anonymous
    val id = token.removePrefix(SANDBOX_TOKEN_PREFIX).toLongOrNull() ?: return Principal.Anonymous
    val roles = loadUser(UserId(id)) ?: return Principal.Anonymous
    return Principal.User(UserId(id), roles)
}
