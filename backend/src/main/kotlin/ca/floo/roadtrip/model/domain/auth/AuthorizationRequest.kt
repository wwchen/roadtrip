package ca.floo.roadtrip.model.domain.auth

/**
 * Everything the caller needs to start an authorization-code flow and then
 * finish it safely.
 *
 * [state], [nonce], and [codeVerifier] are single-use secrets generated per
 * sign-in attempt. They must survive the redirect to the provider and come back
 * unaltered, which the route layer arranges with a short-lived signed cookie —
 * deliberately not a server-side table, since these expire in minutes and a
 * table would need a sweep job.
 *
 * They defend different things and none is redundant:
 *  - [state] proves the callback belongs to a flow this browser started (CSRF).
 *  - [nonce] binds the returned ID token to that same flow (token replay).
 *  - [codeVerifier] proves whoever redeems the code is who requested it (PKCE),
 *    which matters because the code travels through the user agent.
 */
data class AuthorizationRequest(
    /** Fully-formed provider URL to redirect the browser to. */
    val authorizationUrl: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
)
