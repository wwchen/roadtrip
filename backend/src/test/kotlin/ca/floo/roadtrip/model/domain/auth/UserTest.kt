package ca.floo.roadtrip.model.domain.auth

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserTest {
    private fun user(roles: Set<Role>) =
        User(
            id = UserId(1),
            email = "a@example.com",
            displayName = null,
            isEmailVerified = true,
            status = UserStatus.ACTIVE,
            roles = roles,
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )

    @Test
    fun `isAdmin is true when the admin role is present`() {
        assertTrue(user(setOf(Role.ADMIN)).isAdmin)
    }

    @Test
    fun `isAdmin is false without the admin role`() {
        assertFalse(user(emptySet()).isAdmin)
    }
}
