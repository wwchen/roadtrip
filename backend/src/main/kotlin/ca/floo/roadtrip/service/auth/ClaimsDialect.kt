package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken
import ca.floo.roadtrip.support.Dispatchable

/**
 * The one vendor-aware seam in the auth layer.
 *
 * Everything else — discovery, PKCE, code exchange, JWKS, token verification —
 * is plain OIDC and identical across providers. What genuinely differs is how a
 * provider reports the *upstream* identity behind itself: which IdP the user
 * actually authenticated with (Google, Apple, a password) and that IdP's own
 * subject. Auth0 packs it into `sub`; other vendors use separate claims.
 *
 * Confining that to a dialect is what keeps the vendor a config value. Adding a
 * provider is one implementation plus one registry entry — the same shape as
 * adding an availability provider or an ETL vendor.
 *
 * A dialect never decides anything. It reads claims and reports what it found;
 * account linking policy belongs to [UserProvisioningService].
 */
internal interface ClaimsDialect : Dispatchable<ClaimsDialectId> {
    /** Stable slug matching `roadtrip.auth.provider`. */
    val id: String

    /**
     * Human-readable vendor name for UI copy ("Continue with Clerk").
     * Null when there is no brand to show; callers fall back to generic copy.
     */
    val displayName: String?

    /**
     * Whether this provider supports the in-app embedded (resource-owner
     * password) login card. Only Auth0 does; every other vendor authenticates
     * through the full-page hosted flow (`GET /auth/login`). The frontend reads
     * this via `/api/me` to pick the sign-in affordance — embedded card vs
     * hosted redirect — so a provider switch changes the login UX with no
     * frontend deploy. Default false: hosted is the safe, universal path.
     */
    val supportsEmbeddedLogin: Boolean get() = false

    override fun canHandle(key: ClaimsDialectId): Boolean = key.slug == id

    /** Normalizes a verified token into the provider-neutral domain shape. */
    fun toIdentityClaims(token: VerifiedIdToken): IdentityClaims
}
