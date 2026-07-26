package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken

/**
 * Plain OIDC. Reads only standard claims and reports no upstream identity.
 *
 * This is the fallback for any compliant provider we have not written a dialect
 * for, and the reason `roadtrip.auth.provider` can be left at its default: a new
 * provider works immediately, it just cannot contribute the stable upstream key
 * that would make a later vendor migration a clean join. Those accounts fall
 * back to matching on verified email.
 */
internal class StandardClaimsDialect : ClaimsDialect {
    override val id: String = ID

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
        const val ID = "oidc"
    }
}
