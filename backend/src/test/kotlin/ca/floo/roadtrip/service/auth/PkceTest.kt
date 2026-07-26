package ca.floo.roadtrip.service.auth

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val RFC7636_MIN_VERIFIER_LENGTH = 43
private const val RFC7636_MAX_VERIFIER_LENGTH = 128
private val unreservedPattern = Regex("^[A-Za-z0-9._~-]+$")

class PkceTest {
    @Test
    fun `verifiers satisfy RFC 7636 length and character rules`() {
        val verifier = Pkce.newCodeVerifier()

        assertTrue(verifier.length in RFC7636_MIN_VERIFIER_LENGTH..RFC7636_MAX_VERIFIER_LENGTH, "was ${verifier.length}")
        assertTrue(unreservedPattern.matches(verifier), "must be URL-safe without escaping: $verifier")
    }

    @Test
    fun `the challenge is base64url of the SHA-256 of the verifier`() {
        val verifier = Pkce.newCodeVerifier()

        val expected =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        assertEquals(expected, Pkce.challengeFor(verifier))
    }

    @Test
    fun `the challenge is not the verifier`() {
        // A 'plain' challenge would leak the verifier over the redirect, which
        // is exactly what PKCE exists to prevent.
        val verifier = Pkce.newCodeVerifier()

        assertNotEquals(verifier, Pkce.challengeFor(verifier))
    }

    @Test
    fun `every generated secret is distinct`() {
        val samples = List(100) { Pkce.newCodeVerifier() } + List(100) { Pkce.newState() } + List(100) { Pkce.newNonce() }

        assertEquals(samples.size, samples.toSet().size)
    }

    @Test
    fun `state and nonce are URL-safe`() {
        assertTrue(unreservedPattern.matches(Pkce.newState()))
        assertTrue(unreservedPattern.matches(Pkce.newNonce()))
    }
}
