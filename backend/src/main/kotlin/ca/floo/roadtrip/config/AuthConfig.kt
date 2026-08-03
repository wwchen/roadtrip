package ca.floo.roadtrip.config

import ca.floo.roadtrip.model.domain.auth.Role
import java.time.Duration

private const val ISSUER_KEY = "issuer"
private const val CLIENT_ID_KEY = "client-id"
private const val CLIENT_SECRET_KEY = "client-secret"
private const val PROVIDER_KEY = "provider"
private const val PROVIDERS_KEY = "providers"
private const val SESSION_TTL_KEY = "session-ttl"
private const val COOKIE_SECURE_KEY = "cookie-secure"
private const val REALM_KEY = "realm"
private const val EMBEDDED_DOMAIN_KEY = "embedded-domain"
private const val ROLE_EMAILS_KEY = "role-emails"
private const val DEFAULT_PROVIDER = "oidc"
private const val COOKIE_SECURE_DEFAULT = "true"

// The standard Auth0 database connection name.
// Spike-confirmable: once Task 1 has live tenant access, verify the connection
// name matches the Auth0 tenant dashboard value.
private const val DEFAULT_REALM = "Username-Password-Authentication"

private val defaultSessionTtl: Duration = Duration.ofDays(30)

/**
 * OIDC identity-provider settings.
 *
 * [provider] selects a claims dialect and the matching credential block under
 * `providers.<slug>` — the flow itself is plain OIDC for every value. Both
 * vendors' credentials stay configured side by side, so changing vendors (or
 * rolling back) is this one value.
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
    /** Auth0 database connection name for the embedded (resource-owner) flow. */
    val realm: String,
    /**
     * Bare hostname for the embedded auth0-js flow — no scheme, no trailing slash.
     *
     * Defaults to [issuer] with the scheme stripped when
     * [ROADTRIP_AUTH_EMBEDDED_DOMAIN] is unset. The custom domain
     * `auth.roadtrip.floo.ca` is set via that env var in production.
     * Spike-confirmable: once Task 1 has live tenant access, verify that
     * auth0-js accepts this value as its `domain` field.
     */
    val embeddedDomain: String,
    /**
     * Verified emails that are auto-granted a role on sign-in, keyed by role.
     * Grant-only and inert when empty; see UserProvisioningService for how it is
     * applied. Committed config, not a secret — knowing the list grants nothing
     * without control of the address's verified IdP account.
     */
    val roleGrants: Map<Role, Set<String>>,
) {
    companion object {
        fun fromConfig(config: ConfigSection): AuthConfig? {
            val provider = config.valueOrDefault(PROVIDER_KEY, DEFAULT_PROVIDER)
            // Credentials are per-vendor so both vendors stay configured at
            // once: switching (or rolling back) is the provider value alone,
            // never a credential swap. Only the ACTIVE block gates the
            // enabled/disabled decision.
            val vendor = config.section(PROVIDERS_KEY).section(provider)
            val issuer = vendor.value(ISSUER_KEY) ?: return null
            val clientId = vendor.value(CLIENT_ID_KEY) ?: return null
            // The secret is required, not optional: this is a confidential client
            // doing a server-side code exchange, and the flow cookie's signing key
            // is derived from it. A deployment without one is misconfigured, not a
            // public client.
            val clientSecret = vendor.value(CLIENT_SECRET_KEY) ?: return null
            val trimmedIssuer = issuer.trimEnd('/')
            // Derive the embedded-auth hostname from the issuer when no explicit
            // override is provided (strip the scheme: "https://foo.auth0.com" → "foo.auth0.com").
            val defaultEmbeddedDomain = trimmedIssuer.removePrefix("https://").removePrefix("http://")
            val roleGrants = parseRoleGrants(config.section(ROLE_EMAILS_KEY))
            return AuthConfig(
                // Trailing slash stripped so discovery resolves to
                // "$issuer/.well-known/openid-configuration" without doubling up.
                issuer = trimmedIssuer,
                clientId = clientId,
                clientSecret = clientSecret,
                provider = provider,
                sessionTtl = config.duration(SESSION_TTL_KEY, defaultSessionTtl),
                isCookieSecure = config.valueOrDefault(COOKIE_SECURE_KEY, COOKIE_SECURE_DEFAULT).toBoolean(),
                realm = config.valueOrDefault(REALM_KEY, DEFAULT_REALM),
                embeddedDomain = config.valueOrDefault(EMBEDDED_DOMAIN_KEY, defaultEmbeddedDomain),
                roleGrants = roleGrants,
            )
        }

        /**
         * Enumerates the immediate child keys of `role-emails` (each a [Role]
         * wireValue), parsing each into a lowercased email set. Unknown role
         * keys are skipped so a stale config key never fails boot.
         */
        private fun parseRoleGrants(section: ConfigSection): Map<Role, Set<String>> =
            section
                .absoluteKeys()
                .mapNotNull { section.relativeKey(it) }
                .mapNotNull { childKey -> Role.parse(childKey)?.let { it to section.csvSet(childKey).map(String::lowercase).toSet() } }
                .toMap()
    }
}
