package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionServiceTest : SharedDbTest() {
    private val userRepo by lazy { UserRepo(ctx) }
    private val userSessionRepo by lazy { UserSessionRepo(ctx) }
    private var now = OffsetDateTime.parse("2026-07-26T12:00:00Z")
    private var emailSeq = 0

    private val sessionService by lazy {
        SessionService(
            userRepo = userRepo,
            userSessionRepo = userSessionRepo,
            sessionTtl = Duration.ofDays(30),
            clock = { now },
        )
    }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM app_user")
        now = OffsetDateTime.parse("2026-07-26T12:00:00Z")
    }

    private fun newUser(): UserId = userRepo.create(email = "s${emailSeq++}@example.com", displayName = null, isEmailVerified = true).id

    @Test
    fun `an issued session resolves to its user`() {
        val userId = newUser()

        val issued = sessionService.issue(userId)
        val principal = assertNotNull(sessionService.resolve(issued.token))

        assertEquals(userId, principal.userId)
        assertTrue(principal.roles.isEmpty())
        assertEquals(now.plusDays(30), issued.expiresAt)
    }

    @Test
    fun `roles are carried into the principal`() {
        val userId = newUser()
        userRepo.grantRole(userId, Role.ADMIN)

        val principal = assertNotNull(sessionService.resolve(sessionService.issue(userId).token))

        assertEquals(setOf(Role.ADMIN), principal.roles)
    }

    @Test
    fun `two sessions for one user get distinct tokens`() {
        val userId = newUser()

        assertNotEquals(sessionService.issue(userId).token, sessionService.issue(userId).token)
    }

    @Test
    fun `the plaintext token is never persisted`() {
        val userId = newUser()
        val issued = sessionService.issue(userId)

        val stored =
            ctx
                .fetchOne("SELECT encode(token_hash, 'hex') AS h FROM user_session")!!
                .get("h", String::class.java)

        assertTrue(!stored.contains(issued.token), "token_hash must not contain the plaintext token")
    }

    @Test
    fun `an unknown token resolves to null`() {
        newUser()

        assertNull(sessionService.resolve("not-a-real-token"))
    }

    @Test
    fun `a revoked session stops resolving`() {
        val issued = sessionService.issue(newUser())

        assertTrue(sessionService.revoke(issued.token))

        assertNull(sessionService.resolve(issued.token))
    }

    @Test
    fun `a session past its ttl stops resolving`() {
        val issued = sessionService.issue(newUser())
        assertNotNull(sessionService.resolve(issued.token))

        now = now.plusDays(31)

        assertNull(sessionService.resolve(issued.token))
    }

    @Test
    fun `disabling an account invalidates its live sessions immediately`() {
        val userId = newUser()
        val issued = sessionService.issue(userId)
        assertNotNull(sessionService.resolve(issued.token))

        // No session revocation — only the account status changes. Access must
        // stop on the next request, not whenever the session happens to expire.
        userRepo.setStatus(userId, UserStatus.DISABLED)

        assertNull(sessionService.resolve(issued.token))
    }

    @Test
    fun `revokeAllForUser signs out every device without touching other users`() {
        val userId = newUser()
        val otherId = newUser()
        val first = sessionService.issue(userId)
        val second = sessionService.issue(userId)
        val other = sessionService.issue(otherId)

        assertEquals(2, sessionService.revokeAllForUser(userId))

        assertNull(sessionService.resolve(first.token))
        assertNull(sessionService.resolve(second.token))
        assertNotNull(sessionService.resolve(other.token))
    }
}
