package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.WatchCredential
import ca.floo.roadtrip.repo.WatchAccessTokenRepo
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64

private const val TOKEN_BYTES = 32
private const val SHA_256 = "SHA-256"

/**
 * Mints and resolves the capability tokens behind alert-email magic links.
 *
 * Deliberately the same shape as [SessionService], for the same reasons: the
 * token is opaque and random rather than a signed blob, so it can be revoked
 * before expiry; and only its SHA-256 is persisted, so a database leak yields no
 * working link. The plaintext exists in this class and in the user's mailbox,
 * nowhere else.
 *
 * The one structural difference from a session is scope, and it is the point of
 * the class: a resolved token yields a [WatchCredential.MagicLink] naming one
 * watch, never a user. There is no path from a link to "everything this person
 * owns" — a leaked link costs one watch.
 *
 * Tokens are minted per email rather than reused across sends. Storing only the
 * hash means there is nothing to re-read for a second send, and per-send tokens
 * are what makes the storage contract possible rather than a cost of it.
 */
open class WatchAccessTokenService(
    private val tokenRepo: WatchAccessTokenRepo,
    private val ttl: Duration,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {
    private val secureRandom = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A freshly minted link token: [token] goes in the email, nowhere else. */
    data class IssuedWatchToken(
        val token: String,
        val expiresAt: OffsetDateTime,
    )

    open fun issue(watchId: Long): IssuedWatchToken {
        val token = randomToken()
        val expiresAt = clock().plus(ttl)
        tokenRepo.create(watchId, hash(token), expiresAt)
        return IssuedWatchToken(token = token, expiresAt = expiresAt)
    }

    /**
     * Resolves a link token to the watch it authorizes, or null when the token is
     * unknown, expired, or revoked.
     *
     * Records the use as a side effect. That write is best-effort telemetry —
     * "was this link ever followed?" — and never gates the answer, so a failed
     * update must not lock a user out of their own alert.
     */
    open fun resolve(token: String): WatchCredential.MagicLink? {
        val now = clock()
        val row = tokenRepo.findActiveByTokenHash(hash(token), now) ?: return null
        tokenRepo.touchLastUsed(row.id, now)
        return WatchCredential.MagicLink(watchId = row.watchId)
    }

    /**
     * Kills every live link for a watch ahead of its expiry, for a link that
     * reached the wrong inbox. Safe to call for a watch with none — deleting a
     * watch already takes its tokens with it.
     */
    open fun revokeAllForWatch(watchId: Long): Int = tokenRepo.revokeAllForWatch(watchId, clock())

    /** Retires rows past expiry. Intended for a periodic sweep. */
    open fun deleteExpired(): Int = tokenRepo.deleteExpired(clock())

    private fun randomToken(): String {
        val buffer = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }

    private fun hash(token: String): ByteArray = MessageDigest.getInstance(SHA_256).digest(token.toByteArray(Charsets.UTF_8))
}
