package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.support.AuthException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISSUER = "https://tenant.example.com"
private const val CLIENT_ID = "client-abc"
private const val NONCE = "nonce-xyz"
private const val KEY_ID = "k1"
private const val RSA_KEY_SIZE = 2048

/**
 * The auth layer's most security-sensitive unit. Each negative case here is a
 * real attack shape, not a hypothetical.
 */
class IdTokenVerifierTest {
    private val signingKey: RSAKey = RSAKeyGenerator(RSA_KEY_SIZE).keyID(KEY_ID).generate()
    private val jwks = JWKSet(listOf(signingKey.toPublicJWK()))
    private val verifier = IdTokenVerifier(clientId = CLIENT_ID)

    private fun claims(
        issuer: String = ISSUER,
        audience: List<String> = listOf(CLIENT_ID),
        subject: String = "auth0|user-1",
        nonce: String? = NONCE,
        expiresInSeconds: Long = 300,
        azp: String? = null,
        email: String? = "user@example.com",
        isEmailVerified: Boolean = true,
    ): JWTClaimsSet =
        JWTClaimsSet
            .Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject)
            .issueTime(Date())
            .expirationTime(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
            .apply {
                nonce?.let { claim("nonce", it) }
                azp?.let { claim("azp", it) }
                email?.let { claim("email", it) }
                claim("email_verified", isEmailVerified)
                claim("name", "Test User")
            }.build()

    private fun signRs256(
        claimsSet: JWTClaimsSet,
        keyId: String = KEY_ID,
    ): String =
        SignedJWT(
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(keyId)
                .type(JOSEObjectType.JWT)
                .build(),
            claimsSet,
        ).apply { sign(RSASSASigner(signingKey)) }.serialize()

    @Test
    fun `a well-formed token verifies and maps its claims`() {
        val verified = verifier.verify(signRs256(claims()), jwks, ISSUER, NONCE)

        assertEquals("auth0|user-1", verified.subject)
        assertEquals(ISSUER, verified.issuer)
        assertEquals("user@example.com", verified.email)
        assertTrue(verified.isEmailVerified)
        assertEquals("Test User", verified.name)
    }

    @Test
    fun `email_verified false is carried through, not assumed`() {
        val verified = verifier.verify(signRs256(claims(isEmailVerified = false)), jwks, ISSUER, NONCE)

        assertTrue(!verified.isEmailVerified)
    }

    /**
     * Auth0 issues `iss` with a trailing slash while the discovery URL is built
     * from the unslashed form, so the configured string and the token's issuer
     * genuinely differ. Verifying against the discovery document's issuer is
     * what makes that work; comparing against the configured value rejected
     * every Auth0 sign-in with "JWT iss claim value rejected".
     */
    @Test
    fun `the issuer is taken from discovery, so a trailing slash verifies`() {
        val slashed = "$ISSUER/"
        val token = signRs256(claims(issuer = slashed))

        val verified = verifier.verify(token, jwks, slashed, NONCE)

        assertEquals(slashed, verified.issuer)
    }

    @Test
    fun `a token from another issuer is rejected`() {
        val token = signRs256(claims(issuer = "https://evil.example.com"))

        assertFailsWith<AuthException> { verifier.verify(token, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `a token minted for another client is rejected`() {
        val token = signRs256(claims(audience = listOf("some-other-client")))

        assertFailsWith<AuthException> { verifier.verify(token, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `an expired token is rejected`() {
        val token = signRs256(claims(expiresInSeconds = -60))

        assertFailsWith<AuthException> { verifier.verify(token, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `a token echoing a different nonce is rejected`() {
        // Replay: a token legitimately issued for a different sign-in attempt.
        val token = signRs256(claims(nonce = "nonce-from-another-flow"))

        assertFailsWith<AuthException> { verifier.verify(token, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `a token with no nonce at all is rejected`() {
        val token = signRs256(claims(nonce = null))

        assertFailsWith<AuthException> { verifier.verify(token, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `an HMAC-signed token is rejected even though the key id matches`() {
        // Alg confusion: sign with the provider's PUBLIC key as an HMAC secret.
        // A verifier that accepted symmetric algorithms would treat this as
        // valid, since the "secret" is public knowledge.
        val publicKeyBytes = signingKey.toPublicJWK().toJSONString().toByteArray()
        val forged =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(),
                claims(),
            ).apply { sign(MACSigner(publicKeyBytes)) }.serialize()

        assertFailsWith<AuthException> { verifier.verify(forged, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `an unsigned token is rejected`() {
        val unsigned = PlainJWT(claims()).serialize()

        assertFailsWith<AuthException> { verifier.verify(unsigned, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `a token signed by an unknown key is rejected`() {
        val strangerKey = RSAKeyGenerator(RSA_KEY_SIZE).keyID("stranger").generate()
        val forged =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID("stranger").build(),
                claims(),
            ).apply { sign(RSASSASigner(strangerKey)) }.serialize()

        assertFailsWith<AuthException> { verifier.verify(forged, jwks, ISSUER, NONCE) }
    }

    @Test
    fun `multiple audiences require azp to name this client`() {
        val wrongAzp = signRs256(claims(audience = listOf(CLIENT_ID, "other"), azp = "other"))
        assertFailsWith<AuthException> { verifier.verify(wrongAzp, jwks, ISSUER, NONCE) }

        val missingAzp = signRs256(claims(audience = listOf(CLIENT_ID, "other")))
        assertFailsWith<AuthException> { verifier.verify(missingAzp, jwks, ISSUER, NONCE) }

        val correctAzp = signRs256(claims(audience = listOf(CLIENT_ID, "other"), azp = CLIENT_ID))
        assertEquals("auth0|user-1", verifier.verify(correctAzp, jwks, ISSUER, NONCE).subject)
    }

    @Test
    fun `keyIdOf reads the header without verifying, and tolerates junk`() {
        assertEquals(KEY_ID, verifier.keyIdOf(signRs256(claims())))
        assertNull(verifier.keyIdOf("not-a-jwt"))
    }
}
