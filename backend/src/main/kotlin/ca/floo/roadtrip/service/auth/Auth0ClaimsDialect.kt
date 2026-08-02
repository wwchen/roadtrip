package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken

private const val SUBJECT_SEPARATOR = "|"
private const val SUBJECT_PARTS = 2

/**
 * Auth0.
 *
 * Auth0 encodes the connection into `sub` as `<connection>|<upstream subject>`:
 *
 *   google-oauth2|103547991597142817347   → Google, that Google account id
 *   apple|001234.abcd...                  → Apple, that Apple user id
 *   auth0|65a1f...                        → Auth0's own password database
 *
 * Splitting that out is the whole job. The upstream subject is what a future
 * vendor migration joins on: Auth0's `sub` changes when the vendor changes, the
 * Google or Apple account id does not.
 *
 * Connection names are normalized to stable slugs, so a tenant that renames a
 * connection — or uses `google-oauth2` in one environment and a custom name in
 * another — still produces the same [IdentityClaims.upstreamProvider].
 */
internal class Auth0ClaimsDialect : ClaimsDialect {
    override val id: String = ID

    override val displayName: String? = "Auth0"

    // Auth0 is the one vendor with an embedded resource-owner login card; the
    // frontend renders the in-app email/password form for it. See ClaimsDialect.
    override val supportsEmbeddedLogin: Boolean = true

    override fun toIdentityClaims(token: VerifiedIdToken): IdentityClaims {
        val parts = token.subject.split(SUBJECT_SEPARATOR, limit = SUBJECT_PARTS)
        val hasUpstream = parts.size == SUBJECT_PARTS && parts.all { it.isNotBlank() }

        return IdentityClaims(
            subject = token.subject,
            email = token.email,
            isEmailVerified = token.isEmailVerified,
            displayName = token.name,
            upstreamProvider = if (hasUpstream) normalizeConnection(parts[0]) else null,
            upstreamSubject = if (hasUpstream) parts[1] else null,
        )
    }

    /**
     * Maps an Auth0 connection name to a stable slug. Unknown connections pass
     * through lowercased rather than becoming null: an unrecognized enterprise
     * connection is still a real, stable upstream identity worth recording.
     */
    private fun normalizeConnection(connection: String): String {
        val lower = connection.lowercase()
        return connectionSlugs.entries.firstOrNull { (prefix, _) -> lower.startsWith(prefix) }?.value ?: lower
    }

    companion object {
        const val ID = "auth0"

        /**
         * Prefix match, not equality: Auth0 suffixes some connection names per
         * tenant, and `google-oauth2` / `apple` are themselves prefixes of the
         * variants seen in practice.
         */
        private val connectionSlugs =
            linkedMapOf(
                "google" to UpstreamProviders.GOOGLE,
                "apple" to UpstreamProviders.APPLE,
                "auth0" to UpstreamProviders.PASSWORD,
                "username-password" to UpstreamProviders.PASSWORD,
            )
    }
}
