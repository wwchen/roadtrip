package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken

/**
 * Clerk.
 *
 * Clerk keeps `sub` opaque (`user_…`) and its OAuth-application id tokens
 * carry no upstream-connection claims, so this dialect reads only the
 * standard claims and reports no upstream identity. Accounts arriving from a
 * previous vendor link on verified email instead ([UserProvisioningService]).
 * If external-account enrichment via Clerk's Backend API is ever wanted, it
 * belongs here, inside the adapter — not in callers.
 */
internal class ClerkClaimsDialect : ClaimsDialect {
    override val id: String = ID

    override val displayName: String? = "Clerk"

    override fun toIdentityClaims(token: VerifiedIdToken): IdentityClaims =
        IdentityClaims(
            subject = token.subject,
            email = token.email,
            isEmailVerified = token.isEmailVerified,
            displayName = token.name,
            upstreamProvider = null,
            upstreamSubject = null,
        )

    companion object {
        const val ID = "clerk"
    }
}
