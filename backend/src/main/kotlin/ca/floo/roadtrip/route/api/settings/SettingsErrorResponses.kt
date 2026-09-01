package ca.floo.roadtrip.route.api.settings

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.route.common.principal
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.service.settings.SettingsError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

// ── Shared path root ─────────────────────────────────────────────────────────
internal const val API_SETTINGS = "/api/settings"

// ── Error codes ──────────────────────────────────────────────────────────────
internal const val ERROR_INVALID_FIELD = "invalid_field"
internal const val ERROR_SLACK_INVALID_AUTH = "slack_invalid_auth"
internal const val ERROR_ENCRYPTION_UNAVAILABLE = "encryption_unavailable"
internal const val ERROR_SLACK_NOT_CONFIGURED = "slack_not_configured"
internal const val ERROR_SLACK_SEND_FAILED = "slack_send_failed"
internal const val ERROR_EMAIL_SEND_FAILED = "email_send_failed"
internal const val ERROR_RECGOV_NOT_CONFIGURED = "recgov_not_configured"
internal const val ERROR_INVALID_BODY = "invalid_body"
internal const val ERROR_UNAUTHENTICATED = "unauthenticated"

// ── OpenAPI tag ───────────────────────────────────────────────────────────────
internal const val TAG_SETTINGS = "settings"

/**
 * Returns the ambient [Principal.User] or responds with 401 and returns null.
 * The `.access(RouteAccess.User)` interceptor already blocks anonymous requests,
 * so this defensive return is a safety net for System principals.
 */
internal suspend fun ApplicationCall.requireUser(): Principal.User? {
    val p = principal() as? Principal.User
    if (p == null) respondApiError(ERROR_UNAUTHENTICATED, HttpStatusCode.Unauthorized)
    return p
}

/**
 * Maps a [SettingsError] to its status code and error code. One mapper for the
 * whole settings surface, so the notification and booking routes cannot drift on
 * what `encryption_unavailable` means. Must be called from a suspend handler.
 */
internal suspend fun ApplicationCall.respondSettingsError(e: SettingsError) =
    when (e) {
        is SettingsError.InvalidField -> respondApiError(ERROR_INVALID_FIELD, HttpStatusCode.BadRequest, e.message)
        is SettingsError.SlackRejected -> respondApiError(ERROR_SLACK_INVALID_AUTH, HttpStatusCode.BadRequest, e.message)
        is SettingsError.EncryptionUnavailable ->
            respondApiError(ERROR_ENCRYPTION_UNAVAILABLE, HttpStatusCode.ServiceUnavailable, e.message)
        is SettingsError.SlackNotConfigured -> respondApiError(ERROR_SLACK_NOT_CONFIGURED, HttpStatusCode.ServiceUnavailable, e.message)
        is SettingsError.SlackSendFailed -> respondApiError(ERROR_SLACK_SEND_FAILED, HttpStatusCode.BadGateway, e.message)
        is SettingsError.EmailSendFailed -> respondApiError(ERROR_EMAIL_SEND_FAILED, HttpStatusCode.BadGateway, e.message)
        is SettingsError.RecgovNotConfigured -> respondApiError(ERROR_RECGOV_NOT_CONFIGURED, HttpStatusCode.Conflict, e.message)
    }
