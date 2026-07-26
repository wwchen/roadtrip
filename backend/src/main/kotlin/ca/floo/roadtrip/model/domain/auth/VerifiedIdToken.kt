package ca.floo.roadtrip.model.domain.auth

/**
 * An ID token whose signature and required claims have already been checked.
 *
 * Constructing one is an assertion that verification passed — the type exists so
 * unverified claims and verified claims cannot be confused at a call site. Only
 * the OIDC client mints these.
 *
 * [claims] carries the raw payload so a [ca.floo.roadtrip.service.auth.ClaimsDialect]
 * can read vendor-specific entries. That is the one place raw claims are meant to
 * be touched; everything downstream consumes [IdentityClaims] instead.
 */
data class VerifiedIdToken(
    val subject: String,
    val issuer: String,
    val email: String?,
    val isEmailVerified: Boolean,
    val name: String?,
    val claims: Map<String, Any?>,
)

/**
 * Reads a string claim, or null when absent or not a string.
 *
 * An extension rather than a member so [VerifiedIdToken] stays a pure data
 * carrier — claim interpretation belongs to the dialect that calls this.
 */
fun VerifiedIdToken.stringClaim(name: String): String? = claims[name] as? String
