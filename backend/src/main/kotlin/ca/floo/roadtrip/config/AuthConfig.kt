package ca.floo.roadtrip.config

import java.time.Duration

private const val ISSUER_KEY = "issuer"
private const val CLIENT_ID_KEY = "client-id"
private const val CLIENT_SECRET_KEY = "client-secret"
private const val PROVIDER_KEY = "provider"
private const val SESSION_TTL_KEY = "session-ttl"
private const val COOKIE_SECURE_KEY = "cookie-secure"
private const val DEFAULT_PROVIDER = "oidc"
private const val COOKIE_SECURE_DEFAULT = "true"
private val defaultSessionTtl: Duration = Duration.ofDays(30)

/**
 * OIDC identity-provider settings.
 *
 * [provider] selects a claims dialect, not an integration: the flow itself is
 * plain OIDC for every value. `auth0` and `workos` differ only in how the
 * upstream connection is spelled in the token, and `oidc` reads standard claims
 * with no upstream detail. Changing vendors is this value plus [issuer] and a
 * credential pair.
 *
 * [fromConfig] returns null when issuer or client id is absent/blank — a
 * first-class "auth disabled" state, mirroring [SlackConfig.fromConfig]. With
 * auth disabled the app still boots and every anonymous surface behaves
 * normally, so a fresh clone and CI need no tenant provisioned anywhere.
 *
 * The redirect URI is deliberately absent: it is derived from
 * [WebAppConfig.rootUrl], which already carries the correct per-profile origin.
 * Two sources for one value is how redirect-URI mismatches happen.
 */
data class AuthConfig(
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    val provider: String,
    val sessionTtl: Duration,
    val isCookieSecure: Boolean,
) {
    companion object {
        fun fromConfig(config: ConfigSection): AuthConfig? {
            val issuer = config.value(ISSUER_KEY) ?: return null
            val clientId = config.value(CLIENT_ID_KEY) ?: return null
            // The secret is required, not optional: this is a confidential client
            // doing a server-side code exchange, and the flow cookie's signing key
            // is derived from it. A deployment without one is misconfigured, not a
            // public client.
            val clientSecret = config.value(CLIENT_SECRET_KEY) ?: return null
            return AuthConfig(
                // Trailing slash stripped so discovery resolves to
                // "$issuer/.well-known/openid-configuration" without doubling up.
                issuer = issuer.trimEnd('/'),
                clientId = clientId,
                clientSecret = clientSecret,
                provider = config.valueOrDefault(PROVIDER_KEY, DEFAULT_PROVIDER),
                sessionTtl = config.duration(SESSION_TTL_KEY, defaultSessionTtl),
                isCookieSecure = config.valueOrDefault(COOKIE_SECURE_KEY, COOKIE_SECURE_DEFAULT).toBoolean(),
            )
        }
    }
}
