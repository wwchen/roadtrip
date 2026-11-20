package ca.floo.roadtrip.route.auth

import ca.floo.roadtrip.model.api.MeResponseDto
import ca.floo.roadtrip.model.api.MeUserDto
import ca.floo.roadtrip.model.api.PasswordBeginRequestDto
import ca.floo.roadtrip.model.api.PasswordBeginResponseDto
import ca.floo.roadtrip.model.api.PasswordCompleteRequestDto
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.decodeOptionalTextJsonBody
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.auth.AuthController
import ca.floo.roadtrip.service.auth.LoginFlowState
import ca.floo.roadtrip.service.auth.encode
import ca.floo.roadtrip.support.AuthException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.IOException

private const val LOGIN_FAILED_ERROR = "login_failed"
private const val IDENTITY_PROVIDER_UNREACHABLE_ERROR = "identity_provider_unreachable"
private const val LOGIN_INTERNAL_ERROR = "login_internal_error"
private const val INVALID_BODY_ERROR = "invalid_body"
private const val NO_LOGIN_FLOW_DETAIL = "no usable login flow: the flow cookie or the code and state are missing, expired or tampered"
private const val AUTH_DISABLED_ERROR = "auth_not_configured"
private const val RETURN_TO_PARAM = "return_to"
private const val CONNECTION_PARAM = "connection"
private const val CODE_PARAM = "code"
private const val STATE_PARAM = "state"
private const val PROVIDER_ERROR_PARAM = "error"
private const val DEFAULT_RETURN_TO = "/"

private val log = LoggerFactory.getLogger("ca.floo.roadtrip.route.auth")

/**
 * Sign-in surface: `/auth/login`, `/auth/callback`, `/auth/logout`, `/api/me`.
 *
 * These are additive. No existing route changes behaviour, and nothing that
 * worked before now requires a session — enforcement arrives with the authz
 * pass.
 *
 * [wiring] is null when no identity provider is configured. That is a supported
 * state, not an error: the routes still mount, `/api/me` reports
 * `auth_enabled: false`, and the login endpoints answer 503. A fresh clone and
 * CI therefore boot and serve every anonymous surface with no tenant anywhere.
 */
internal fun Route.authRoutes(wiring: AuthRouteWiring?) {
    route("/auth") {
        get("/login") {
            val auth = wiring ?: return@get call.respondAuthDisabled()

            val connection = call.queryParam(CONNECTION_PARAM)?.takeIf { it in auth.allowedConnections }

            // A provider that is unreachable or misconfigured must not 500 into
            // the user's face; it is an operator problem, not a client error.
            val start =
                runCatching {
                    auth.authController.beginLogin(call.queryParam(RETURN_TO_PARAM), connection)
                }.getOrElse { failure ->
                    log.error("could not begin login", failure)
                    return@get call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadGateway)
                }

            call.response.setLoginFlowCookie(start.flow.encode(auth.flowSigningKey), auth.isCookieSecure)
            call.respondRedirect(start.authorizationUrl)
        }.access(RouteAccess.Anonymous)

        get("/callback") {
            val auth = wiring ?: return@get call.respondAuthDisabled()

            // Single-use secrets: clear them on every outcome, so a replayed
            // callback cannot reuse a live flow.
            val flowCookie = call.request.loginFlowCookie()
            call.response.clearLoginFlowCookie(auth.isCookieSecure)

            // The provider reports user-facing refusals (consent denied, and so
            // on) as a query parameter, not an HTTP error.
            call.queryParam(PROVIDER_ERROR_PARAM)?.let { providerError ->
                log.warn("identity provider refused the authorization request: {}", providerError)
                return@get call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.Unauthorized, providerError)
            }

            val code = call.queryParam(CODE_PARAM)
            val state = call.queryParam(STATE_PARAM)
            val flow = flowCookie?.let { LoginFlowState.decode(it, auth.flowSigningKey) }
            if (code == null || state == null || flow == null) {
                // Includes a tampered or expired flow cookie — indistinguishable
                // from a missing one, deliberately.
                log.warn("callback without a usable login flow")
                return@get call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadRequest, NO_LOGIN_FLOW_DETAIL)
            }

            val result =
                try {
                    auth.authController.completeLogin(code, state, flow)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@get call.respondLoginFailure("login", e)
                }

            call.response.setSessionCookie(
                token = result.session.token,
                isSecure = auth.isCookieSecure,
                maxAgeSeconds = auth.sessionMaxAgeSeconds,
            )
            call.respondRedirect(result.returnTo)
        }.access(RouteAccess.Anonymous)

        get("/logout") {
            val auth = wiring ?: return@get call.respondAuthDisabled()

            auth.authController.logout(call.request.sessionToken())
            call.response.clearSessionCookie(auth.isCookieSecure)

            // Ending the provider's own session too, where it supports it, so
            // "log out" does not silently leave the user one click from being
            // signed straight back in.
            val providerLogout =
                auth.appRootUrl?.let { root ->
                    try {
                        auth.authController.providerLogoutUrl(root)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("provider logout URL unavailable; ending only the local session", e)
                        null
                    }
                }
            call.respondRedirect(providerLogout ?: DEFAULT_RETURN_TO)
        }.access(RouteAccess.Anonymous)

        post("/password/begin") {
            val auth = wiring ?: return@post call.respondAuthDisabled()
            val body =
                when (
                    val parsed =
                        call.decodeOptionalTextJsonBody<PasswordBeginRequestDto?>(roadtripApiJson) { null }
                ) {
                    is RouteBodyResult.Invalid ->
                        return@post call.respondApiError(INVALID_BODY_ERROR, HttpStatusCode.BadRequest, parsed.detail)
                    is RouteBodyResult.Valid -> parsed.value
                }
            val start =
                runCatching { auth.authController.beginPasswordLogin(body?.returnTo) }
                    .getOrElse { failure ->
                        log.error("could not begin password login", failure)
                        return@post call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadGateway)
                    }
            call.response.setLoginFlowCookie(start.flow.encode(auth.flowSigningKey), auth.isCookieSecure)
            call.respond(
                PasswordBeginResponseDto(
                    state = start.flow.state,
                    nonce = start.flow.nonce,
                    codeChallenge = start.passwordChallenge,
                    redirectUri = auth.redirectUri,
                ),
            )
        }.describeApi(
            tag = "auth",
            summary = "Start an embedded password login: mint the flow and hand back its challenge",
            description = "Answers 502 when the provider refuses and 503 when no identity provider is configured.",
        ).access(RouteAccess.Anonymous)

        post("/password/complete") {
            val auth = wiring ?: return@post call.respondAuthDisabled()

            val flowCookie = call.request.loginFlowCookie()
            call.response.clearLoginFlowCookie(auth.isCookieSecure)

            val body =
                when (val parsed = call.receiveJsonBody<PasswordCompleteRequestDto>()) {
                    is RouteBodyResult.Invalid ->
                        return@post call.respondApiError(INVALID_BODY_ERROR, HttpStatusCode.BadRequest, parsed.detail)
                    is RouteBodyResult.Valid -> parsed.value
                }
            val flow =
                flowCookie?.let { LoginFlowState.decode(it, auth.flowSigningKey) }
                    ?: run {
                        log.warn("password/complete without a usable login flow")
                        return@post call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadRequest, NO_LOGIN_FLOW_DETAIL)
                    }

            val result =
                try {
                    auth.authController.completeLogin(body.code, body.state, flow)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@post call.respondLoginFailure("password login", e)
                }

            call.response.setSessionCookie(
                token = result.session.token,
                isSecure = auth.isCookieSecure,
                maxAgeSeconds = auth.sessionMaxAgeSeconds,
            )
            call.respond(HttpStatusCode.NoContent)
        }.describeApi(
            tag = "auth",
            summary = "Exchange the login code for a session cookie; answers 204 with no body",
            description =
                "The flow cookie set by the begin route is required and is cleared either way. " +
                    "401 means the provider refused the code; 502 means the provider could not be reached; " +
                    "500 means roadtrip failed while signing in; 503 means no identity provider is configured.",
        ).access(RouteAccess.Anonymous)
    }

    route("/api") {
        get("/me") {
            if (wiring == null) {
                return@get call.respondEncodedJson(
                    MeResponseDto(isAuthenticated = false, isAuthEnabled = false),
                )
            }
            when (val principal = wiring.authController.resolve(call.request.sessionToken())) {
                is Principal.User -> call.respondEncodedJson(wiring.meResponse(principal))
                else ->
                    call.respondEncodedJson(
                        MeResponseDto(
                            isAuthenticated = false,
                            authClientId = wiring.authClientId,
                            authDomain = wiring.authDomain,
                            authRealm = wiring.authRealm,
                            providerLabel = wiring.providerLabel,
                            isEmbeddedLogin = wiring.isEmbeddedLogin,
                        ),
                    )
            }
        }.describeApi("auth", "Describe the current caller")
            .access(RouteAccess.Anonymous)
    }
}

private suspend fun ApplicationCall.respondLoginFailure(
    stage: String,
    failure: Exception,
) {
    when (failure) {
        is AuthException -> {
            log.warn("{} refused", stage, failure)
            respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.Unauthorized)
        }
        is IOException -> {
            log.error("{} could not reach the identity provider", stage, failure)
            respondApiError(IDENTITY_PROVIDER_UNREACHABLE_ERROR, HttpStatusCode.BadGateway, failure.described())
        }
        else -> {
            log.error("{} failed inside roadtrip", stage, failure)
            respondApiError(LOGIN_INTERNAL_ERROR, HttpStatusCode.InternalServerError, failure.described())
        }
    }
}

private fun Throwable.described(): String = "${this::class.simpleName}: $message"

private fun AuthRouteWiring.meResponse(principal: Principal.User): MeResponseDto =
    meResponseForUser(
        authController.currentUser(principal),
        principal,
        isAuthEnabled = true,
        authClientId = authClientId,
        authDomain = authDomain,
        authRealm = authRealm,
        providerLabel = providerLabel,
        isEmbeddedLogin = isEmbeddedLogin,
    )

private fun meResponseForUser(
    user: User?,
    principal: Principal.User,
    isAuthEnabled: Boolean,
    authClientId: String? = null,
    authDomain: String? = null,
    authRealm: String? = null,
    providerLabel: String? = null,
    isEmbeddedLogin: Boolean = false,
): MeResponseDto =
    MeResponseDto(
        isAuthenticated = user != null,
        isAuthEnabled = isAuthEnabled,
        user =
            user?.let {
                MeUserDto(
                    id = it.id.value,
                    email = it.email,
                    displayName = it.displayName,
                    isEmailVerified = it.isEmailVerified,
                    roles = principal.roles.map { role -> role.wireValue },
                    theme = it.theme,
                )
            },
        authClientId = authClientId,
        authDomain = authDomain,
        authRealm = authRealm,
        providerLabel = providerLabel,
        isEmbeddedLogin = isEmbeddedLogin,
    )

private suspend fun ApplicationCall.respondAuthDisabled() =
    respondApiError(
        AUTH_DISABLED_ERROR,
        HttpStatusCode.ServiceUnavailable,
        "no identity provider is configured",
    )

/**
 * Everything the auth routes need, resolved once at wiring time.
 *
 * Bundled rather than passed as loose parameters because its absence is the
 * "auth disabled" signal — one nullable value the routes branch on, instead of
 * several that could disagree.
 */
internal class AuthRouteWiring(
    val authController: AuthController,
    val flowSigningKey: ByteArray,
    val isCookieSecure: Boolean,
    val sessionMaxAgeSeconds: Int,
    val appRootUrl: String?,
    /** Public non-secret auth config surfaced on /api/me for the embedded login flow. */
    val authClientId: String,
    val authDomain: String,
    val authRealm: String,
    /** The redirect_uri the backend uses at code-exchange time. Surfaced on
     *  /auth/password/begin so the frontend passes the identical value to
     *  auth0-js — one source of truth prevents redirect_uri mismatches. */
    val redirectUri: String,
    /** Human-readable provider name for login UI copy; null when unbranded. */
    val providerLabel: String?,
    /** True when the active provider uses the embedded login card (Auth0);
     *  false routes the frontend to the full-page hosted flow. */
    val isEmbeddedLogin: Boolean,
    /** Connection slugs /auth/login may forward. Unknown values are silently
     *  dropped — the browser falls through to the provider's own login page. */
    val allowedConnections: Set<String>,
)
