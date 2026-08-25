package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.WatchManagementTokenRepo
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64

private const val TOKEN_BYTES = 32
private const val SHA_256 = "SHA-256"
private val DEFAULT_TOKEN_TTL: Duration = Duration.ofDays(30)

/**
 * Mints and resolves the magic-link token embedded in alert emails so a
 * recipient can pause/resume/delete the one watch that token was minted for,
 * without signing in.
 *
 * Follows the same shape as [ca.floo.roadtrip.service.auth.SessionService]:
 * the token is opaque and random, not a JWT, and only its SHA-256 hash is
 * persisted, so a database leak yields no usable token and the plaintext
 * never reaches a log or a query plan. Unlike a session, a resolved token
 * proves scope over exactly one watch id — not an identity — so callers must
 * still check the resolved watch id matches the one being acted on.
 */
class WatchManagementTokenService(
    private val watchManagementTokenRepo: WatchManagementTokenRepo,
    private val tokenTtl: Duration = DEFAULT_TOKEN_TTL,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    private val secureRandom = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    data class IssuedToken(
        val token: String,
        val expiresAt: OffsetDateTime,
    )

    fun issue(watchId: Long): IssuedToken {
        val token = randomToken()
        val expiresAt = clock().plus(tokenTtl)
        watchManagementTokenRepo.create(watchId, hash(token), expiresAt)
        return IssuedToken(token = token, expiresAt = expiresAt)
    }

    /** Resolves a token to the single watch id it grants management of, or null. */
    fun resolve(token: String): Long? = watchManagementTokenRepo.findActiveByTokenHash(hash(token), clock())?.watchId

    private fun randomToken(): String {
        val buffer = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }

    private fun hash(token: String): ByteArray = MessageDigest.getInstance(SHA_256).digest(token.toByteArray(Charsets.UTF_8))
}
