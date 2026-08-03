package ca.floo.roadtrip.model.domain.auth

import java.time.OffsetDateTime

/**
 * The `app_user` account record as a domain entity — distinct from
 * [Principal.User], which is the thin request-auth identity (id + roles).
 * Loaded by [ca.floo.roadtrip.repo.UserRepo] when a caller needs the account,
 * not just "who is calling".
 */
data class User(
    val id: UserId,
    val email: String,
    val displayName: String?,
    val isEmailVerified: Boolean,
    val status: UserStatus,
    val roles: Set<Role>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    /** Convenience for the one coarse role we model today. */
    val isAdmin: Boolean get() = Role.ADMIN in roles
}
