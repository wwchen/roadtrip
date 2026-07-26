package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.auth.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserSessionRepoTest : SharedDbTest() {
    private val userRepo by lazy { UserRepo(ctx) }
    private val userSessionRepo by lazy { UserSessionRepo(ctx) }
    private var emailSeq = 0

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-26T12:00:00Z")

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM app_user")
    }

    private fun newUser(): UserId =
        userRepo
            .create(email = "session-${emailSeq++}@example.com", displayName = null, isEmailVerified = true)
            .id

    private fun hash(seed: String): ByteArray = seed.toByteArray()

    @Test
    fun `an unexpired unrevoked session resolves`() {
        val userId = newUser()
        val created = userSessionRepo.create(userId, hash("live"), now.plusDays(30))

        val found = assertNotNull(userSessionRepo.findActiveByTokenHash(hash("live"), now))

        assertEquals(created.id, found.id)
        assertEquals(userId, found.userId)
        assertNull(found.revokedAt)
    }

    @Test
    fun `an expired session does not resolve`() {
        val userId = newUser()
        userSessionRepo.create(userId, hash("stale"), now.minusSeconds(1))

        assertNull(userSessionRepo.findActiveByTokenHash(hash("stale"), now))
    }

    @Test
    fun `a revoked session does not resolve`() {
        val userId = newUser()
        userSessionRepo.create(userId, hash("revoked"), now.plusDays(30))

        assertTrue(userSessionRepo.revokeByTokenHash(hash("revoked"), now))

        assertNull(userSessionRepo.findActiveByTokenHash(hash("revoked"), now))
    }

    @Test
    fun `revoking twice is a no-op`() {
        val userId = newUser()
        userSessionRepo.create(userId, hash("twice"), now.plusDays(30))

        assertTrue(userSessionRepo.revokeByTokenHash(hash("twice"), now))
        assertFalse(
            userSessionRepo.revokeByTokenHash(hash("twice"), now),
            "an already-revoked session should not be revoked again",
        )
    }

    @Test
    fun `revokeAllForUser kills every live session and leaves other users alone`() {
        val userId = newUser()
        val otherId = newUser()
        userSessionRepo.create(userId, hash("a"), now.plusDays(30))
        userSessionRepo.create(userId, hash("b"), now.plusDays(30))
        userSessionRepo.create(otherId, hash("c"), now.plusDays(30))

        assertEquals(2, userSessionRepo.revokeAllForUser(userId, now))

        assertTrue(userSessionRepo.listActiveForUser(userId, now).isEmpty())
        assertEquals(1, userSessionRepo.listActiveForUser(otherId, now).size)
    }

    @Test
    fun `an unknown token hash resolves to null`() {
        newUser()

        assertNull(userSessionRepo.findActiveByTokenHash(hash("never-issued"), now))
    }

    @Test
    fun `deleteExpired drops expired rows and keeps revoked-but-unexpired ones`() {
        val userId = newUser()
        userSessionRepo.create(userId, hash("expired"), now.minusSeconds(1))
        userSessionRepo.create(userId, hash("revoked-live"), now.plusDays(30))
        userSessionRepo.revokeByTokenHash(hash("revoked-live"), now)

        assertEquals(1, userSessionRepo.deleteExpired(now))

        // Still present, still dead — it must keep answering until expiry.
        assertNull(userSessionRepo.findActiveByTokenHash(hash("revoked-live"), now))
        assertEquals(
            1L,
            ctx.fetchOne("SELECT count(*) AS c FROM user_session")!!.get("c", Long::class.java),
        )
    }

    @Test
    fun `sessions cascade when the user is deleted`() {
        val userId = newUser()
        userSessionRepo.create(userId, hash("cascade"), now.plusDays(30))

        ctx.execute("DELETE FROM app_user WHERE id = ?", userId.value)

        assertNull(userSessionRepo.findActiveByTokenHash(hash("cascade"), now))
    }
}
