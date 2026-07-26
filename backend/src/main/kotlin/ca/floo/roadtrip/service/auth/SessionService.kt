package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64

private const val TOKEN_BYTES = 32
private const val SHA_256 = "SHA-256"

/**
 * Mints and resolves first-party sessions.
 *
 * The session token is opaque and random — not a JWT. Two consequences that
 * motivated the choice: it can be revoked before expiry (a signed stateless
 * cookie cannot), and it survives an identity-provider swap untouched, so
 * changing vendors does not sign everybody out.
 *
 * Only the SHA-256 of a token is persisted. The plaintext exists in this class
 * and in the user's cookie, nowhere else — so a database leak yields no usable
 * session, and a token never reaches a log or a query plan.
 */
class SessionService(
    private val userRepo: UserRepo,
    private val userSessionRepo: UserSessionRepo,
    private val sessionTtl: Duration,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    private val secureRandom = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A freshly minted session: [token] goes in the cookie, nowhere else. */
    data class IssuedSession(
        val token: String,
        val expiresAt: OffsetDateTime,
    )

    fun issue(userId: UserId): IssuedSession {
        val token = randomToken()
        val expiresAt = clock().plus(sessionTtl)
        userSessionRepo.create(userId, hash(token), expiresAt)
        return IssuedSession(token = token, expiresAt = expiresAt)
    }

    /**
     * Resolves a cookie value to its principal, or null when the token is
     * unknown, expired, revoked, or belongs to a disabled account.
     *
     * The disabled-account check is deliberately here rather than only at
     * sign-in: disabling an account must take effect on the next request, not
     * whenever the user's existing sessions happen to expire.
     */
    fun resolve(token: String): Principal.User? {
        val session = userSessionRepo.findActiveByTokenHash(hash(token), clock()) ?: return null
        val user = userRepo.findById(session.userId) ?: return null
        if (user.status != UserStatus.ACTIVE) return null
        return Principal.User(userId = user.id, roles = user.roles)
    }

    /** Logout. Safe to call with an unknown or already-revoked token. */
    fun revoke(token: String): Boolean = userSessionRepo.revokeByTokenHash(hash(token), clock())

    /** Sign out everywhere — also the correct response to a credential change. */
    fun revokeAllForUser(userId: UserId): Int = userSessionRepo.revokeAllForUser(userId, clock())

    /** Retires rows past expiry. Intended for a periodic sweep. */
    fun deleteExpired(): Int = userSessionRepo.deleteExpired(clock())

    private fun randomToken(): String {
        val buffer = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }

    private fun hash(token: String): ByteArray = MessageDigest.getInstance(SHA_256).digest(token.toByteArray(Charsets.UTF_8))
}
