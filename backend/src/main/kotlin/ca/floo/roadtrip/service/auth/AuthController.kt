package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.config.AuthConfig
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.support.AuthException

private const val DEFAULT_RETURN_TO = "/"
private const val PATH_PREFIX = "/"
private const val PROTOCOL_RELATIVE_PREFIX = "//"
private const val BACKSLASH = '\\'

/**
 * Orchestrates sign-in. Routes own cookies and status codes; this owns the
 * sequence and the policy.
 *
 * Deliberately knows nothing about Ktor, so the whole flow is testable without
 * an HTTP server.
 */
internal class AuthController(
    private val config: AuthConfig,
    private val identityProviderRegistry: IdentityProviderRegistry,
    private val userProvisioningService: UserProvisioningService,
    private val sessionService: SessionService,
    private val userRepo: UserRepo,
) {
    /** A started sign-in: where to send the browser, and what to remember. */
    data class LoginStart(
        val authorizationUrl: String,
        val flow: LoginFlowState,
    )

    /** A started embedded password login: the flow to remember, and the PKCE
     *  challenge the in-page adapter forwards to the provider. */
    data class PasswordLoginStart(
        val flow: LoginFlowState,
        val passwordChallenge: String,
    )

    /** A completed sign-in: the session to hand the browser, and where to land. */
    data class LoginResult(
        val session: SessionService.IssuedSession,
        val returnTo: String,
    )

    /**
     * Starts a flow. [rawReturnTo] comes from the query string and is
     * untrusted — [sanitizeReturnTo] reduces it to a same-origin path.
     *
     * [connection] is an optional provider-specific connection hint. The caller
     * is responsible for allowlisting it before passing it here (unknown values
     * are silently dropped at the route layer; see [AuthRoutes]).
     *
     * The URL and the flow secrets come from one call deliberately: asking the
     * provider twice would mint a second, different state/nonce/verifier and the
     * callback could never match.
     */
    suspend fun beginLogin(
        rawReturnTo: String?,
        connection: String? = null,
    ): LoginStart {
        val returnTo = sanitizeReturnTo(rawReturnTo)
        val request = identityProviderRegistry.active().authorizationRequest(returnTo, connection)
        return LoginStart(
            authorizationUrl = request.authorizationUrl,
            flow =
                LoginFlowState(
                    state = request.state,
                    nonce = request.nonce,
                    codeVerifier = request.codeVerifier,
                    returnTo = returnTo,
                ),
        )
    }

    /**
     * Completes a flow: verifies [returnedState] against the flow cookie,
     * redeems the code, provisions the user, and issues a session.
     *
     * @throws AuthException when state does not match, or any downstream step
     *         fails. Routes translate this into one generic failure — telling a
     *         caller *which* check failed just tells an attacker what to fix.
     */
    suspend fun completeLogin(
        code: String,
        returnedState: String,
        flow: LoginFlowState,
    ): LoginResult {
        if (returnedState != flow.state) {
            throw AuthException("authorization state does not match the flow that started this login")
        }

        val claims =
            identityProviderRegistry.active().exchange(
                code = code,
                codeVerifier = flow.codeVerifier,
                expectedNonce = flow.nonce,
            )
        val userId = userProvisioningService.provision(config.provider, claims)
        return LoginResult(
            session = sessionService.issue(userId),
            returnTo = sanitizeReturnTo(flow.returnTo),
        )
    }

    /**
     * Starts an embedded password login. Identical flow-secret minting to
     * [beginLogin], but returns the PKCE challenge rather than a redirect URL: the
     * browser talks to the provider in-page, so there is nowhere to redirect. The
     * verifier stays server-side in the signed flow cookie.
     */
    suspend fun beginPasswordLogin(rawReturnTo: String?): PasswordLoginStart {
        val returnTo = sanitizeReturnTo(rawReturnTo)
        val request = identityProviderRegistry.active().authorizationRequest(returnTo)
        return PasswordLoginStart(
            flow =
                LoginFlowState(
                    state = request.state,
                    nonce = request.nonce,
                    codeVerifier = request.codeVerifier,
                    returnTo = returnTo,
                ),
            passwordChallenge = Pkce.challengeFor(request.codeVerifier),
        )
    }

    /** The stored account behind a resolved principal, or null once it is gone. */
    fun currentUser(principal: Principal.User): User? = userRepo.findById(principal.userId)

    fun resolve(sessionToken: String?): Principal {
        val token = sessionToken?.takeIf { it.isNotBlank() } ?: return Principal.Anonymous
        return sessionService.resolve(token) ?: Principal.Anonymous
    }

    fun logout(sessionToken: String?) {
        sessionToken?.takeIf { it.isNotBlank() }?.let { sessionService.revoke(it) }
    }

    /** Provider-side logout URL, or null when it advertises none. */
    suspend fun providerLogoutUrl(returnToAbsoluteUrl: String): String? = identityProviderRegistry.active().logoutUrl(returnToAbsoluteUrl)

    /**
     * Reduces an untrusted `return_to` to a safe same-origin path.
     *
     * Anything that is not a plain absolute path becomes [DEFAULT_RETURN_TO].
     * Rejecting `//host` and backslashes matters specifically: both are read as
     * an authority by some browsers, which would turn our login endpoint into an
     * open redirect — a credible phishing primitive precisely because the link
     * genuinely starts at our domain.
     */
    private fun sanitizeReturnTo(rawReturnTo: String?): String {
        val candidate = rawReturnTo?.trim().orEmpty()
        if (candidate.isEmpty()) return DEFAULT_RETURN_TO
        if (!candidate.startsWith(PATH_PREFIX)) return DEFAULT_RETURN_TO
        if (candidate.startsWith(PROTOCOL_RELATIVE_PREFIX)) return DEFAULT_RETURN_TO
        if (candidate.contains(BACKSLASH)) return DEFAULT_RETURN_TO
        return candidate
    }
}
