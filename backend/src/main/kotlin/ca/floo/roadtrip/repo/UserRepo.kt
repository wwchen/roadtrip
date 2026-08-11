package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.AppUser.Companion.APP_USER
import ca.floo.roadtrip.db.generated.tables.UserRole.Companion.USER_ROLE
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.OffsetDateTime

/**
 * Persistence for `app_user` and its roles.
 *
 * Roles live here rather than in a repo of their own: `user_role` is part of the
 * user's persistence surface, and an entity repo owns its entity's full surface.
 *
 * Email is matched case-insensitively through the `app_user_email_lower_uq`
 * expression index. Every read and write normalizes through [normalizeEmail] so
 * the index is actually used and two casings can never become two accounts.
 */
open class UserRepo(
    private val ctx: DSLContext,
) {
    /**
     * Lists only the seeded sandbox users (those whose email ends with
     * `@sandbox.local`).  Used by [sandboxRoutes] so that even a mis-scoped
     * snapshot cannot leak real user rows through the `/api/sandbox/users`
     * endpoint.
     */
    open fun listSandboxUsers(): List<User> =
        ctx
            .select(APP_USER.fields().toList())
            .from(APP_USER)
            .where(DSL.lower(APP_USER.EMAIL).like("%@sandbox.local"))
            .fetch()
            .map { fromRecord(it, rolesFor(UserId(it.get(APP_USER.ID)!!))) }

    open fun findById(id: UserId): User? =
        ctx
            .select(APP_USER.fields().toList())
            .from(APP_USER)
            .where(APP_USER.ID.eq(id.value))
            .fetchOne()
            ?.let { fromRecord(it, rolesFor(id)) }

    /** Case-insensitive lookup. [email] need not be pre-normalized. */
    fun findByEmail(email: String): User? =
        ctx
            .select(APP_USER.fields().toList())
            .from(APP_USER)
            .where(DSL.lower(APP_USER.EMAIL).eq(normalizeEmail(email)))
            .fetchOne()
            ?.let { fromRecord(it, rolesFor(UserId(it.get(APP_USER.ID)!!))) }

    fun create(
        email: String,
        displayName: String?,
        isEmailVerified: Boolean,
    ): User {
        val id =
            ctx
                .insertInto(APP_USER)
                .set(APP_USER.EMAIL, normalizeEmail(email))
                .set(APP_USER.DISPLAY_NAME, displayName)
                .set(APP_USER.EMAIL_VERIFIED, isEmailVerified)
                .returningResult(APP_USER.ID)
                .fetchOne()!!
                .value1()!!
        return findById(UserId(id))!!
    }

    /**
     * Records that the provider asserted this user's address. One-way: an
     * already-verified user is never demoted by a later unverified sign-in,
     * because that would let an unverified identity strip a verification the
     * user legitimately earned.
     */
    fun markEmailVerified(id: UserId): Boolean =
        ctx
            .update(APP_USER)
            .set(APP_USER.EMAIL_VERIFIED, true)
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(id.value))
            .and(APP_USER.EMAIL_VERIFIED.isFalse)
            .execute() > 0

    open fun updateProfile(
        id: UserId,
        displayName: String?,
        theme: String? = null,
    ): User? {
        ctx
            .update(APP_USER)
            .set(APP_USER.DISPLAY_NAME, displayName)
            // Null means "unchanged", so coalesce to the stored value rather than
            // writing a null the NOT NULL column would reject.
            .set(APP_USER.THEME, DSL.coalesce(DSL.value(theme), APP_USER.THEME))
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(id.value))
            .execute()
        return findById(id)
    }

    fun setStatus(
        id: UserId,
        status: UserStatus,
    ): Boolean =
        ctx
            .update(APP_USER)
            .set(APP_USER.STATUS, status.wireValue)
            .set(APP_USER.UPDATED_AT, OffsetDateTime.now())
            .where(APP_USER.ID.eq(id.value))
            .execute() > 0

    /** Idempotent — granting a role the user already holds is a no-op. */
    fun grantRole(
        id: UserId,
        role: Role,
    ): Boolean =
        ctx
            .insertInto(USER_ROLE)
            .set(USER_ROLE.USER_ID, id.value)
            .set(USER_ROLE.ROLE, role.wireValue)
            .onConflictDoNothing()
            .execute() > 0

    fun revokeRole(
        id: UserId,
        role: Role,
    ): Boolean =
        ctx
            .deleteFrom(USER_ROLE)
            .where(USER_ROLE.USER_ID.eq(id.value))
            .and(USER_ROLE.ROLE.eq(role.wireValue))
            .execute() > 0

    fun rolesFor(id: UserId): Set<Role> =
        ctx
            .select(USER_ROLE.ROLE)
            .from(USER_ROLE)
            .where(USER_ROLE.USER_ID.eq(id.value))
            .fetch()
            .mapNotNull { Role.parse(it.value1()) }
            .toSet()

    private fun fromRecord(
        record: Record,
        roles: Set<Role>,
    ): User =
        User(
            id = UserId(record.get(APP_USER.ID)!!),
            email = record.get(APP_USER.EMAIL)!!,
            displayName = record.get(APP_USER.DISPLAY_NAME),
            theme = record.get(APP_USER.THEME)!!,
            isEmailVerified = record.get(APP_USER.EMAIL_VERIFIED)!!,
            // An unparseable status means the CHECK constraint and this enum have
            // drifted; failing loudly beats silently treating it as active.
            status =
                requireNotNull(UserStatus.parse(record.get(APP_USER.STATUS))) {
                    "unknown app_user.status '${record.get(APP_USER.STATUS)}'"
                },
            roles = roles,
            createdAt = record.get(APP_USER.CREATED_AT)!!,
            updatedAt = record.get(APP_USER.UPDATED_AT)!!,
        )

    private companion object {
        /** Lowercase + trim. Must match the `app_user_email_lower_uq` index. */
        fun normalizeEmail(email: String): String = email.trim().lowercase()
    }
}
