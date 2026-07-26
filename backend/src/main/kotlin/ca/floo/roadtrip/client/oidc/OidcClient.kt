package ca.floo.roadtrip.client.oidc

import ca.floo.roadtrip.model.auth.OidcDiscoveryDto
import ca.floo.roadtrip.model.auth.OidcTokenResponseDto
import ca.floo.roadtrip.support.AuthException
import com.nimbusds.jose.jwk.JWKSet
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private const val DISCOVERY_PATH = "/.well-known/openid-configuration"
private const val GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code"
private val metadataTtl: Duration = Duration.ofHours(1)

/**
 * Transport for the OIDC endpoints: discovery, token exchange, and JWKS.
 *
 * Speaks only the standard, so it works against any compliant provider. Nothing
 * here knows which vendor is configured — that distinction lives in
 * [ca.floo.roadtrip.service.auth.ClaimsDialect].
 *
 * Discovery and JWKS are cached for [metadataTtl]. [jwks] additionally accepts
 * a forced refresh so a caller that meets an unknown key id can re-fetch once
 * rather than fail: providers rotate signing keys without warning, and a stale
 * cache would otherwise reject every login until the TTL lapsed.
 */
class OidcClient(
    private val issuer: String,
    private val httpClient: HttpClient = defaultClient(),
    private val clock: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val discoveryCache = AtomicReference<Cached<OidcDiscoveryDto>?>(null)
    private val jwksCache = AtomicReference<Cached<JWKSet>?>(null)

    private data class Cached<T>(
        val value: T,
        val fetchedAt: Instant,
    )

    suspend fun discovery(): OidcDiscoveryDto {
        discoveryCache.get()?.takeIf { it.isFresh() }?.let { return it.value }

        val url = "$issuer$DISCOVERY_PATH"
        val response = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw AuthException("OIDC discovery failed: ${response.status.value} from $url")
        }
        val document =
            runCatching { json.decodeFromString(OidcDiscoveryDto.serializer(), response.bodyAsText()) }
                .getOrElse { throw AuthException("OIDC discovery document at $url is not parseable", it) }

        // A document whose `issuer` disagrees with where we fetched it is either
        // a misconfiguration or a spoof; either way its endpoints are untrusted.
        if (document.issuer.trimEnd('/') != issuer.trimEnd('/')) {
            throw AuthException("OIDC discovery issuer '${document.issuer}' does not match configured issuer '$issuer'")
        }
        discoveryCache.set(Cached(document, clock()))
        return document
    }

    /**
     * Fetches the provider's signing keys. Pass [forceRefresh] when a token
     * names a key id the cached set does not contain.
     */
    suspend fun jwks(forceRefresh: Boolean = false): JWKSet {
        if (!forceRefresh) {
            jwksCache.get()?.takeIf { it.isFresh() }?.let { return it.value }
        }

        val url = discovery().jwksUri
        val response = httpClient.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw AuthException("JWKS fetch failed: ${response.status.value} from $url")
        }
        val keys =
            runCatching { JWKSet.parse(response.bodyAsText()) }
                .getOrElse { throw AuthException("JWKS at $url is not parseable", it) }

        jwksCache.set(Cached(keys, clock()))
        return keys
    }

    /**
     * Redeems an authorization code. [codeVerifier] is the PKCE secret whose
     * challenge was sent with the authorization request.
     *
     * Client authentication uses `client_secret_post`. [clientSecret] may be
     * blank for a public client, in which case PKCE alone authenticates the
     * exchange.
     */
    suspend fun exchangeCode(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String,
        codeVerifier: String,
    ): OidcTokenResponseDto {
        val tokenEndpoint = discovery().tokenEndpoint
        val response =
            httpClient.submitForm(
                url = tokenEndpoint,
                formParameters =
                    Parameters.build {
                        append("grant_type", GRANT_TYPE_AUTHORIZATION_CODE)
                        append("code", code)
                        append("redirect_uri", redirectUri)
                        append("client_id", clientId)
                        append("code_verifier", codeVerifier)
                        if (clientSecret.isNotBlank()) append("client_secret", clientSecret)
                    },
            )
        val body = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            // The body can carry the client secret back in an error echo on some
            // providers, so only the status is surfaced.
            throw AuthException("Token exchange failed: ${response.status.value} from $tokenEndpoint")
        }
        return runCatching { json.decodeFromString(OidcTokenResponseDto.serializer(), body) }
            .getOrElse { throw AuthException("Token response from $tokenEndpoint is not parseable", it) }
    }

    private fun <T> Cached<T>.isFresh(): Boolean = Duration.between(fetchedAt, clock()) < metadataTtl

    companion object {
        fun defaultClient(): HttpClient = HttpClient(CIO)
    }
}
