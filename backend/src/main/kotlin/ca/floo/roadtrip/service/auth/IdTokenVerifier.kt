package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken
import ca.floo.roadtrip.support.AuthException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor

private const val NONCE_CLAIM = "nonce"
private const val EMAIL_CLAIM = "email"
private const val EMAIL_VERIFIED_CLAIM = "email_verified"
private const val NAME_CLAIM = "name"
private const val AZP_CLAIM = "azp"
private const val SUBJECT_CLAIM = "sub"
private const val ISSUED_AT_CLAIM = "iat"
private const val EXPIRY_CLAIM = "exp"

/**
 * Verifies an OIDC ID token and converts it into a [VerifiedIdToken].
 *
 * Only asymmetric signature algorithms are accepted. That exclusion is
 * load-bearing rather than tidiness: permitting an HMAC algorithm alongside RSA
 * enables the classic alg-confusion attack, where a token is signed with the
 * provider's *public* key as the HMAC secret and verifies successfully. `none`
 * is excluded for the same reason.
 *
 * Verification order matters — the signature is checked before any claim is
 * read, so no unverified attacker-controlled value ever reaches a decision.
 */
class IdTokenVerifier(
    private val clientId: String,
) {
    /** Key id named by the token header, or null when unsigned/unparseable. */
    fun keyIdOf(idToken: String): String? = runCatching { SignedJWT.parse(idToken).header.keyID }.getOrNull()

    /**
     * @param issuer the `issuer` from the provider's discovery document, not the
     *        configured issuer string. OIDC requires the two to match byte for
     *        byte, and the configured value cannot: it is normalized for URL
     *        construction, and providers differ on the trailing slash — Auth0
     *        issues `https://tenant.auth0.com/` while the discovery URL is built
     *        from the unslashed form. Comparing against the configured string
     *        rejected every Auth0 token.
     * @param expectedNonce the nonce issued with the authorization request; the
     *        token must echo it exactly, which is what stops a token minted for
     *        a different sign-in attempt from being replayed into this one.
     */
    fun verify(
        idToken: String,
        jwks: JWKSet,
        issuer: String,
        expectedNonce: String,
    ): VerifiedIdToken {
        val processor = DefaultJWTProcessor<SecurityContext>()
        processor.jwsKeySelector =
            JWSVerificationKeySelector(allowedAlgorithms, ImmutableJWKSet(jwks))
        processor.jwtClaimsSetVerifier =
            DefaultJWTClaimsVerifier(
                clientId,
                JWTClaimsSet.Builder().issuer(issuer).build(),
                setOf(SUBJECT_CLAIM, ISSUED_AT_CLAIM, EXPIRY_CLAIM),
            )

        val claims =
            runCatching { processor.process(idToken, null) }
                .getOrElse { throw AuthException("ID token verification failed: ${it.message}", it) }

        verifyNonce(claims, expectedNonce)
        verifyAuthorizedParty(claims)

        val subject =
            claims.subject?.takeIf { it.isNotBlank() }
                ?: throw AuthException("ID token has no subject")

        return VerifiedIdToken(
            subject = subject,
            issuer = claims.issuer,
            email = claims.getStringClaimOrNull(EMAIL_CLAIM),
            isEmailVerified = claims.getBooleanClaimOrFalse(EMAIL_VERIFIED_CLAIM),
            name = claims.getStringClaimOrNull(NAME_CLAIM),
            claims = claims.claims,
        )
    }

    private fun verifyNonce(
        claims: JWTClaimsSet,
        expectedNonce: String,
    ) {
        val nonce = claims.getStringClaimOrNull(NONCE_CLAIM)
        if (nonce != expectedNonce) {
            throw AuthException("ID token nonce does not match the authorization request")
        }
    }

    /**
     * When a token carries more than one audience, OIDC requires `azp` to name
     * the client the token was actually issued for. Without this check a token
     * minted for a different client in the same tenant would pass the audience
     * test purely by listing us alongside its real target.
     */
    private fun verifyAuthorizedParty(claims: JWTClaimsSet) {
        if (claims.audience.orEmpty().size <= 1) return
        val azp = claims.getStringClaimOrNull(AZP_CLAIM)
        if (azp != clientId) {
            throw AuthException("ID token has multiple audiences and azp does not name this client")
        }
    }

    private fun JWTClaimsSet.getStringClaimOrNull(name: String): String? =
        runCatching { getStringClaim(name) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JWTClaimsSet.getBooleanClaimOrFalse(name: String): Boolean = runCatching { getBooleanClaim(name) }.getOrNull() ?: false

    private companion object {
        /**
         * Asymmetric algorithms only. See the class comment — including any
         * HMAC algorithm here would open an alg-confusion hole.
         */
        private val allowedAlgorithms =
            setOf(
                JWSAlgorithm.RS256,
                JWSAlgorithm.RS384,
                JWSAlgorithm.RS512,
                JWSAlgorithm.ES256,
                JWSAlgorithm.ES384,
                JWSAlgorithm.ES512,
                JWSAlgorithm.PS256,
                JWSAlgorithm.PS384,
                JWSAlgorithm.PS512,
            )
    }
}
