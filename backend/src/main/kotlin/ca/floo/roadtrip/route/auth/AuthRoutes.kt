package ca.floo.roadtrip.route.auth

import ca.floo.roadtrip.model.api.MeResponseDto
import ca.floo.roadtrip.model.api.MeUserDto
import ca.floo.roadtrip.model.api.PasswordBeginRequestDto
import ca.floo.roadtrip.model.api.PasswordBeginResponseDto
import ca.floo.roadtrip.model.api.PasswordCompleteRequestDto
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.principal
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.auth.AuthController
import ca.floo.roadtrip.service.auth.LoginFlowState
import ca.floo.roadtrip.service.auth.encode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

private const val LOGIN_FAILED_ERROR = "login_failed"
private const val AUTH_DISABLED_ERROR = "auth_not_configured"
private const val RETURN_TO_PARAM = "return_to"
private const val CONNECTION_PARAM = "connection"
private const val CODE_PARAM = "code"
private const val STATE_PARAM = "state"
private const val PROVIDER_ERROR_PARAM = "error"
private const val DEFAULT_RETURN_TO = "/"

/** Allowlist of connection slugs that may be forwarded to the provider.
 *  Unknown values are silently dropped — the browser falls through to the
 *  provider's own login page rather than getting an error. */
private val allowedConnections = setOf("google-oauth2")

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
internal fun Route.authRoutes(
    wiring: AuthRouteWiring?,
    userRepo: UserRepo,
) {
    route("/auth") {
        get("/login") {
            val auth = wiring ?: return@get call.respondAuthDisabled()

            val connection = call.queryParam(CONNECTION_PARAM)?.takeIf { it in allowedConnections }

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
                return@get call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.Unauthorized)
            }

            val code = call.queryParam(CODE_PARAM)
            val state = call.queryParam(STATE_PARAM)
            val flow = flowCookie?.let { LoginFlowState.decode(it, auth.flowSigningKey) }
            if (code == null || state == null || flow == null) {
                // Includes a tampered or expired flow cookie — indistinguishable
                // from a missing one, deliberately.
                log.warn("callback without a usable login flow")
                return@get call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadRequest)
            }

            val result =
                runCatching { auth.authController.completeLogin(code, state, flow) }
                    .getOrElse { failure ->
                        // The message names which check failed and is for
                        // operators; the client is told only that it failed.
                        log.warn("login could not be completed: {}", failure.message)
                        return@get call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.Unauthorized)
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
                    runCatching { auth.authController.providerLogoutUrl(root) }.getOrNull()
                }
            call.respondRedirect(providerLogout ?: DEFAULT_RETURN_TO)
        }.access(RouteAccess.Anonymous)

        post("/password/begin") {
            val auth = wiring ?: return@post call.respondAuthDisabled()
            val body = runCatching { call.receive<PasswordBeginRequestDto>() }.getOrNull()
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
        }.access(RouteAccess.Anonymous)

        post("/password/complete") {
            val auth = wiring ?: return@post call.respondAuthDisabled()

            val flowCookie = call.request.loginFlowCookie()
            call.response.clearLoginFlowCookie(auth.isCookieSecure)

            val body = runCatching { call.receive<PasswordCompleteRequestDto>() }.getOrNull()
            val flow = flowCookie?.let { LoginFlowState.decode(it, auth.flowSigningKey) }
            if (body == null || flow == null) {
                log.warn("password/complete without a usable flow or body")
                return@post call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.BadRequest)
            }

            val result =
                runCatching { auth.authController.completeLogin(body.code, body.state, flow) }
                    .getOrElse { failure ->
                        log.warn("password login could not be completed: {}", failure.message)
                        return@post call.respondApiError(LOGIN_FAILED_ERROR, HttpStatusCode.Unauthorized)
                    }

            call.response.setSessionCookie(
                token = result.session.token,
                isSecure = auth.isCookieSecure,
                maxAgeSeconds = auth.sessionMaxAgeSeconds,
            )
            call.respond(HttpStatusCode.NoContent)
        }.access(RouteAccess.Anonymous)
    }

    route("/api") {
        get("/me") {
            if (wiring == null) {
                // Auth off. Normally Anonymous, but a sandbox may have assumed a user
                // via the ambient principal. Report it, while keeping auth "disabled".
                return@get when (val p = call.principal()) {
                    is Principal.User ->
                        call.respondEncodedJson(roadtripApiJson, meResponseForUser(userRepo, p, isAuthEnabled = false))
                    else ->
                        call.respondEncodedJson(roadtripApiJson, MeResponseDto(isAuthenticated = false, isAuthEnabled = false))
                }
            }
            when (val principal = wiring.authController.resolve(call.request.sessionToken())) {
                is Principal.User -> call.respondEncodedJson(roadtripApiJson, wiring.meResponse(principal))
                else ->
                    call.respondEncodedJson(
                        roadtripApiJson,
                        MeResponseDto(
                            isAuthenticated = false,
                            authClientId = wiring.authClientId,
                            authDomain = wiring.authDomain,
                            authRealm = wiring.authRealm,
                            providerLabel = wiring.providerLabel,
                        ),
                    )
            }
        }.describeApi("auth", "Describe the current caller")
            .access(RouteAccess.Anonymous)
    }
}

private fun AuthRouteWiring.meResponse(principal: Principal.User): MeResponseDto =
    meResponseForUser(
        userRepo,
        principal,
        isAuthEnabled = true,
        authClientId = authClientId,
        authDomain = authDomain,
        authRealm = authRealm,
        providerLabel = providerLabel,
    )

private fun meResponseForUser(
    userRepo: UserRepo,
    principal: Principal.User,
    isAuthEnabled: Boolean,
    authClientId: String? = null,
    authDomain: String? = null,
    authRealm: String? = null,
    providerLabel: String? = null,
): MeResponseDto {
    val user = userRepo.findById(principal.userId)
    return MeResponseDto(
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
                )
            },
        authClientId = authClientId,
        authDomain = authDomain,
        authRealm = authRealm,
        providerLabel = providerLabel,
    )
}

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
    val userRepo: UserRepo,
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
)
