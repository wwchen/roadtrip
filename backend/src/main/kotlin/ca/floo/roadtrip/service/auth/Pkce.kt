package ca.floo.roadtrip.service.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val VERIFIER_BYTES = 32
private const val STATE_BYTES = 32
private const val NONCE_BYTES = 32
private const val SHA_256 = "SHA-256"

/**
 * PKCE (RFC 7636) plus the other per-attempt secrets a sign-in needs.
 *
 * All values come from [SecureRandom] and are base64url-encoded without
 * padding, which both satisfies the spec's unreserved-character requirement and
 * keeps them safe to put in a URL or a cookie without further escaping.
 *
 * 32 bytes yields a 43-character verifier — the minimum RFC 7636 allows, and
 * comfortably beyond guessing.
 */
internal object Pkce {
    private val secureRandom = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun newCodeVerifier(): String = randomToken(VERIFIER_BYTES)

    fun newState(): String = randomToken(STATE_BYTES)

    fun newNonce(): String = randomToken(NONCE_BYTES)

    /**
     * S256 challenge: base64url(SHA-256(ASCII(verifier))).
     *
     * The `plain` method is not offered. It transmits the verifier itself, which
     * defeats the point of PKCE whenever the channel that carries the redirect
     * can be observed.
     */
    fun challengeFor(codeVerifier: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }
}
