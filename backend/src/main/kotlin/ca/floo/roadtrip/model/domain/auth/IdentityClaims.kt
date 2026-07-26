package ca.floo.roadtrip.model.domain.auth

/**
 * Verified identity facts from an OIDC provider, normalized to a shape that
 * carries no vendor structure. This is the abstraction boundary: the adapter
 * that spoke to Auth0 or WorkOS produces one of these, and nothing downstream
 * can tell which vendor it came from.
 *
 * [upstreamProvider] / [upstreamSubject] describe the IdP *behind* the
 * aggregator — the Google or Apple account, as opposed to the aggregator's own
 * record of it. Vendors spell this differently (Auth0 encodes it into `sub` as
 * `google-oauth2|1234`; WorkOS reports it separately), which is why extracting
 * it belongs to a per-vendor claims dialect rather than to this model. Both are
 * nullable: a provider that does not expose upstream identity leaves them unset
 * and the account falls back to matching on verified email.
 */
data class IdentityClaims(
    /** The provider's `sub` claim. Unique within that provider. */
    val subject: String,
    val email: String?,
    /** Whether the provider asserted the address. Gates account linking. */
    val isEmailVerified: Boolean,
    val displayName: String?,
    /** `"google"` | `"apple"` | `"password"`, or null when not exposed. */
    val upstreamProvider: String? = null,
    /** The upstream IdP's own subject, or null when not exposed. */
    val upstreamSubject: String? = null,
)
