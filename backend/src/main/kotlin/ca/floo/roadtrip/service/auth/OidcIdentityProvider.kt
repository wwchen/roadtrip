package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.client.oidc.OidcClient
import ca.floo.roadtrip.config.AuthConfig
import ca.floo.roadtrip.model.domain.auth.AuthorizationRequest
import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import io.ktor.http.URLBuilder
import io.ktor.http.Url

private const val RESPONSE_TYPE_CODE = "code"
private const val CODE_CHALLENGE_METHOD_S256 = "S256"
private const val SCOPE = "openid email profile"
private const val POST_LOGOUT_REDIRECT_PARAM = "post_logout_redirect_uri"
private const val CLIENT_ID_PARAM = "client_id"

/**
 * The generic OIDC provider — the only [IdentityProvider] implementation, and
 * the reason the vendor is a config value.
 *
 * Every endpoint comes from discovery, so pointing [AuthConfig.issuer] at a
 * different compliant provider is the whole of a swap. The vendor's name appears
 * once, in [AuthConfig.provider], and only to select a [ClaimsDialect].
 */
internal class OidcIdentityProvider(
    private val config: AuthConfig,
    private val redirectUri: String,
    private val oidcClient: OidcClient,
    private val idTokenVerifier: IdTokenVerifier,
    private val claimsDialect: ClaimsDialect,
) : IdentityProvider {
    override val id: String = ID

    override suspend fun authorizationRequest(returnTo: String): AuthorizationRequest {
        val codeVerifier = Pkce.newCodeVerifier()
        val state = Pkce.newState()
        val nonce = Pkce.newNonce()

        val url =
            URLBuilder(Url(oidcClient.discovery().authorizationEndpoint))
                .apply {
                    parameters.append("response_type", RESPONSE_TYPE_CODE)
                    parameters.append(CLIENT_ID_PARAM, config.clientId)
                    parameters.append("redirect_uri", redirectUri)
                    parameters.append("scope", SCOPE)
                    parameters.append("state", state)
                    parameters.append("nonce", nonce)
                    parameters.append("code_challenge", Pkce.challengeFor(codeVerifier))
                    parameters.append("code_challenge_method", CODE_CHALLENGE_METHOD_S256)
                }.buildString()

        return AuthorizationRequest(
            authorizationUrl = url,
            state = state,
            nonce = nonce,
            codeVerifier = codeVerifier,
        )
    }

    override suspend fun exchange(
        code: String,
        codeVerifier: String,
        expectedNonce: String,
    ): IdentityClaims {
        val tokens =
            oidcClient.exchangeCode(
                code = code,
                redirectUri = redirectUri,
                clientId = config.clientId,
                clientSecret = config.clientSecret,
                codeVerifier = codeVerifier,
            )

        // Providers rotate signing keys without notice. When the token names a
        // key the cached set lacks, re-fetch once before concluding it is bad —
        // otherwise a rotation rejects every sign-in until the cache expires.
        val keyId = idTokenVerifier.keyIdOf(tokens.idToken)
        val cached = oidcClient.jwks()
        val jwks =
            if (keyId != null && cached.getKeyByKeyId(keyId) == null) {
                oidcClient.jwks(forceRefresh = true)
            } else {
                cached
            }

        val verified =
            idTokenVerifier.verify(
                idToken = tokens.idToken,
                jwks = jwks,
                issuer = oidcClient.discovery().issuer,
                expectedNonce = expectedNonce,
            )
        return claimsDialect.toIdentityClaims(verified)
    }

    override suspend fun logoutUrl(returnTo: String): String? {
        val endSessionEndpoint = oidcClient.discovery().endSessionEndpoint ?: return null
        return URLBuilder(Url(endSessionEndpoint))
            .apply {
                parameters.append(CLIENT_ID_PARAM, config.clientId)
                parameters.append(POST_LOGOUT_REDIRECT_PARAM, returnTo)
            }.buildString()
    }

    companion object {
        const val ID = "oidc"
    }
}
