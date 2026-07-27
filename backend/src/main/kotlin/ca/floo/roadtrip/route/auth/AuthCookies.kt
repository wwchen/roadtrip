package ca.floo.roadtrip.route.auth

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.ApplicationResponse
import io.ktor.util.date.GMTDate

internal const val SESSION_COOKIE = "rt_session"
internal const val LOGIN_FLOW_COOKIE = "rt_login"

/** A login attempt that has not completed in ten minutes is abandoned. */
private const val LOGIN_FLOW_MAX_AGE_SECONDS = 600
private const val EXPIRE_IMMEDIATELY_SECONDS = 0
private const val COOKIE_PATH = "/"

/**
 * Cookie mechanics for the auth routes.
 *
 * Both cookies are `HttpOnly` — no client script ever needs them, and the
 * frontend learns who you are from `/api/me` rather than by reading a cookie.
 *
 * `SameSite=Lax` rather than `Strict` is required, not a compromise: the
 * provider redirects the browser back to `/auth/callback` cross-site, and a
 * Strict cookie would not be sent on that navigation, so every login would fail
 * to find its flow. Lax still withholds both cookies from cross-site
 * subrequests, which is what CSRF protection actually needs.
 *
 * Values are written with [CookieEncoding.RAW]: both are already base64url and
 * Ktor's default URI encoding would otherwise re-encode them on the way out but
 * not on the way in.
 */
internal fun ApplicationResponse.setSessionCookie(
    token: String,
    isSecure: Boolean,
    maxAgeSeconds: Int,
) = cookies.append(
    Cookie(
        name = SESSION_COOKIE,
        value = token,
        maxAge = maxAgeSeconds,
        path = COOKIE_PATH,
        secure = isSecure,
        httpOnly = true,
        encoding = CookieEncoding.RAW,
        extensions = mapOf(SAME_SITE to SAME_SITE_LAX),
    ),
)

internal fun ApplicationResponse.clearSessionCookie(isSecure: Boolean) = expire(SESSION_COOKIE, isSecure)

internal fun ApplicationResponse.setLoginFlowCookie(
    value: String,
    isSecure: Boolean,
) = cookies.append(
    Cookie(
        name = LOGIN_FLOW_COOKIE,
        value = value,
        maxAge = LOGIN_FLOW_MAX_AGE_SECONDS,
        path = COOKIE_PATH,
        secure = isSecure,
        httpOnly = true,
        encoding = CookieEncoding.RAW,
        extensions = mapOf(SAME_SITE to SAME_SITE_LAX),
    ),
)

/**
 * Clears the flow cookie. Called on every callback outcome, success or failure —
 * the secrets it carries are single-use, and leaving them live would let a
 * replayed callback reuse them.
 */
internal fun ApplicationResponse.clearLoginFlowCookie(isSecure: Boolean) = expire(LOGIN_FLOW_COOKIE, isSecure)

internal fun ApplicationRequest.sessionToken(): String? = cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() }

internal fun ApplicationRequest.loginFlowCookie(): String? = cookies[LOGIN_FLOW_COOKIE]?.takeIf { it.isNotBlank() }

/**
 * True when the request's own origin is same-site, or absent.
 *
 * Belt-and-braces alongside `SameSite=Lax` for state-changing requests: Lax
 * already blocks cross-site form posts, and this rejects anything that names a
 * different origin outright.
 */
internal fun ApplicationCall.hasSameOriginOrNoOrigin(expectedOrigin: String?): Boolean {
    val origin = request.headers[ORIGIN_HEADER] ?: return true
    if (expectedOrigin == null) return true
    return origin.trimEnd('/').equals(expectedOrigin.trimEnd('/'), ignoreCase = true)
}

private fun ApplicationResponse.expire(
    name: String,
    isSecure: Boolean,
) = cookies.append(
    Cookie(
        name = name,
        value = "",
        maxAge = EXPIRE_IMMEDIATELY_SECONDS,
        expires = GMTDate.START,
        path = COOKIE_PATH,
        secure = isSecure,
        httpOnly = true,
        encoding = CookieEncoding.RAW,
        extensions = mapOf(SAME_SITE to SAME_SITE_LAX),
    ),
)

private const val SAME_SITE = "SameSite"
private const val SAME_SITE_LAX = "Lax"
private const val ORIGIN_HEADER = "Origin"
