package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken
import ca.floo.roadtrip.model.domain.auth.stringClaim

/**
 * WorkOS AuthKit.
 *
 * Unlike Auth0, WorkOS keeps `sub` opaque and reports the upstream connection in
 * separate claims. This dialect reads the connection type and the upstream
 * identifier from the claim names WorkOS documents, normalizing the type onto
 * the same slugs [Auth0ClaimsDialect] produces so both vendors write comparable
 * `user_identity` rows.
 *
 * **Unverified against a live tenant.** RFC 0009 flags this as an open question:
 * the claim names below need confirming against a real WorkOS token before this
 * dialect is trusted in production. The failure mode is deliberately soft —
 * absent claims yield a null upstream rather than an error, so sign-in still
 * works and those accounts simply fall back to verified-email matching if the
 * vendor is ever swapped. Sign-in never breaks because a claim name was wrong;
 * only migration fidelity degrades.
 */
internal class WorkOsClaimsDialect : ClaimsDialect {
    override val id: String = ID

    override fun toIdentityClaims(token: VerifiedIdToken): IdentityClaims {
        val connectionType =
            connectionTypeClaims.firstNotNullOfOrNull { token.stringClaim(it) }
        val upstreamSubject =
            upstreamSubjectClaims.firstNotNullOfOrNull { token.stringClaim(it) }

        return IdentityClaims(
            subject = token.subject,
            email = token.email,
            isEmailVerified = token.isEmailVerified,
            displayName = token.name,
            // Only claim an upstream identity when both halves are present; a
            // provider name without a stable subject is not a join key.
            upstreamProvider = connectionType?.let(::normalizeConnectionType)?.takeIf { upstreamSubject != null },
            upstreamSubject = upstreamSubject?.takeIf { connectionType != null },
        )
    }

    private fun normalizeConnectionType(connectionType: String): String {
        val lower = connectionType.lowercase()
        return connectionSlugs.entries.firstOrNull { (fragment, _) -> lower.contains(fragment) }?.value ?: lower
    }

    companion object {
        const val ID = "workos"

        /** Candidate claim names, most specific first. */
        private val connectionTypeClaims = listOf("connection_type", "provider", "idp")
        private val upstreamSubjectClaims = listOf("idp_id", "external_id", "connection_user_id")

        private val connectionSlugs =
            linkedMapOf(
                "google" to UpstreamProviders.GOOGLE,
                "apple" to UpstreamProviders.APPLE,
                "password" to UpstreamProviders.PASSWORD,
                "authkit" to UpstreamProviders.PASSWORD,
            )
    }
}
