package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.AuthorizationRequest
import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.support.Dispatchable

/**
 * Where identity comes from.
 *
 * The port exists so the vendor stops at this boundary: callers hand it a code
 * and receive [IdentityClaims], with no way to observe which provider answered.
 * That is what makes swapping vendors a config change — and what keeps the rest
 * of the app free of any identity SDK.
 *
 * The only implementation is [OidcIdentityProvider], which covers every
 * standards-compliant provider. An interface with one implementation would
 * normally be over-engineering; here it earns its place by naming the seam a
 * non-OIDC provider would have to satisfy, and by keeping vendor concepts from
 * leaking upward as the layer grows.
 *
 * Methods suspend because a compliant provider is discovered at runtime, not
 * configured endpoint by endpoint.
 */
internal interface IdentityProvider : Dispatchable<IdentityProviderId> {
    /** Stable slug identifying this provider. */
    val id: String

    override fun canHandle(key: IdentityProviderId): Boolean = key.slug == id

    /**
     * Begins a sign-in. [returnTo] is the app-relative path to land on
     * afterwards; the caller is responsible for having validated it as
     * same-origin before it gets here.
     *
     * The returned [AuthorizationRequest] carries single-use secrets the caller
     * must persist across the redirect and hand back to [exchange].
     */
    suspend fun authorizationRequest(returnTo: String): AuthorizationRequest

    /**
     * Completes a sign-in: redeems [code], verifies the resulting ID token, and
     * normalizes it.
     *
     * [codeVerifier] and [expectedNonce] must be the values from this flow's
     * [AuthorizationRequest]. Passing values from a different attempt must fail,
     * which is the property that makes a stolen code useless on its own.
     *
     * @throws ca.floo.roadtrip.support.AuthException on any failure. Callers
     *         translate it into a generic error — the message names which check
     *         failed and is for operators only.
     */
    suspend fun exchange(
        code: String,
        codeVerifier: String,
        expectedNonce: String,
    ): IdentityClaims

    /**
     * Provider-side logout URL, or null when the provider advertises none — in
     * which case clearing the local session is the whole of logout.
     */
    suspend fun logoutUrl(returnTo: String): String?
}
