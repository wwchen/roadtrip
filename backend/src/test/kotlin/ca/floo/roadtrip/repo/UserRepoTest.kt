package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRepoTest : SharedDbTest() {
    private val userRepo by lazy { UserRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        // Identities, sessions, and roles cascade from app_user.
        ctx.execute("DELETE FROM app_user")
    }

    @Test
    fun `create stores a normalized email and defaults to active`() {
        val user = userRepo.create(email = "  Ada@Example.COM ", displayName = "Ada", isEmailVerified = true)

        assertEquals("ada@example.com", user.email)
        assertEquals("Ada", user.displayName)
        assertTrue(user.isEmailVerified)
        assertEquals(UserStatus.ACTIVE, user.status)
        assertTrue(user.roles.isEmpty())
    }

    @Test
    fun `findByEmail matches regardless of casing or surrounding space`() {
        val created = userRepo.create(email = "grace@example.com", displayName = null, isEmailVerified = false)

        assertEquals(created.id, userRepo.findByEmail("GRACE@EXAMPLE.COM")?.id)
        assertEquals(created.id, userRepo.findByEmail(" grace@example.com ")?.id)
        assertNull(userRepo.findByEmail("someone-else@example.com"))
    }

    @Test
    fun `two casings of one address cannot become two accounts`() {
        userRepo.create(email = "dup@example.com", displayName = null, isEmailVerified = false)

        val second = runCatching { userRepo.create("DUP@EXAMPLE.COM", null, false) }

        assertTrue(second.isFailure, "expected app_user_email_lower_uq to reject the duplicate")
    }

    @Test
    fun `markEmailVerified is one-way`() {
        val user = userRepo.create(email = "unverified@example.com", displayName = null, isEmailVerified = false)

        assertTrue(userRepo.markEmailVerified(user.id))
        assertTrue(userRepo.findById(user.id)!!.isEmailVerified)

        // Already verified: no second write, and verification is never cleared.
        assertFalse(userRepo.markEmailVerified(user.id))
        assertTrue(userRepo.findById(user.id)!!.isEmailVerified)
    }

    @Test
    fun `grantRole is idempotent and revokeRole removes it`() {
        val user = userRepo.create(email = "admin@example.com", displayName = null, isEmailVerified = true)

        assertTrue(userRepo.grantRole(user.id, Role.ADMIN))
        assertFalse(userRepo.grantRole(user.id, Role.ADMIN), "second grant should be a no-op")
        assertEquals(setOf(Role.ADMIN), userRepo.findById(user.id)!!.roles)

        assertTrue(userRepo.revokeRole(user.id, Role.ADMIN))
        assertTrue(userRepo.findById(user.id)!!.roles.isEmpty())
    }

    @Test
    fun `setStatus disables an account`() {
        val user = userRepo.create(email = "disabled@example.com", displayName = null, isEmailVerified = true)

        assertTrue(userRepo.setStatus(user.id, UserStatus.DISABLED))

        val reloaded = assertNotNull(userRepo.findById(user.id))
        assertEquals(UserStatus.DISABLED, reloaded.status)
    }

    @Test
    fun `a new user defaults to the system theme`() {
        val user = userRepo.create(email = "theme-default@example.com", displayName = null, isEmailVerified = true)

        assertEquals("system", user.theme)
    }

    @Test
    fun `updateProfile persists a theme`() {
        val user = userRepo.create(email = "theme-set@example.com", displayName = null, isEmailVerified = true)

        val updated = userRepo.updateProfile(user.id, displayName = "Wm", theme = "dark")

        assertEquals("dark", updated?.theme)
        assertEquals("dark", userRepo.findById(user.id)?.theme)
    }

    @Test
    fun `updateProfile with a null theme leaves the stored theme unchanged`() {
        val user = userRepo.create(email = "theme-coalesce@example.com", displayName = null, isEmailVerified = true)
        userRepo.updateProfile(user.id, displayName = "Wm", theme = "dark")

        val updated = userRepo.updateProfile(user.id, displayName = "Wm2", theme = null)

        assertEquals("dark", updated?.theme)
        assertEquals("dark", userRepo.findById(user.id)?.theme)
    }

    @Test
    fun `the theme CHECK constraint rejects an illegal value`() {
        val user = userRepo.create(email = "theme-illegal@example.com", displayName = null, isEmailVerified = true)

        val result =
            runCatching {
                ctx.execute("UPDATE app_user SET theme = 'sepia' WHERE id = ${user.id.value}")
            }

        assertTrue(result.isFailure, "expected app_user_theme_check to reject an illegal theme value")
    }
}
