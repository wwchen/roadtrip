package ca.floo.roadtrip.route.api.settings

import ca.floo.roadtrip.model.api.RecgovMfaRequest
import ca.floo.roadtrip.model.api.UpdateRecgovRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.decodeTextJsonBody
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.settings.RecGovCredentialPort
import ca.floo.roadtrip.service.settings.SettingsError
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

// ── Path segments, relative to /api/settings/recgov ──────────────────────────
private const val SEGMENT_RECGOV = "/recgov"
private const val LOGIN_PATH = "/login"
private const val MFA_PATH = "/login/mfa"
private const val VERIFY_PATH = "/verify"
private const val STATUS_PATH = "/status"

/**
 * Per-user rec.gov credential routes.
 *
 * All `RouteAccess.User`, mirroring [settingsRoutes]: parse, call
 * [RecGovCredentialPort], map [SettingsError], serialize a DTO. No handler here
 * knows the companion exists.
 *
 * `GET /status` is deliberately separate from `GET /api/settings` — it is the
 * one read that talks to the companion, and opening Settings must not wait on
 * it. A blocked login (MFA, captcha, an unreachable companion) is a 200 with a
 * `status` and a code, not an HTTP error: it is an answer to what the button
 * asked. Only "there is nothing stored" and "there is no encryption key" are
 * HTTP errors.
 */
internal fun Route.recgovSettingsRoutes(service: RecGovCredentialPort) {
    route(API_SETTINGS + SEGMENT_RECGOV) {
        put {
            val principal = call.requireUser() ?: return@put
            val req =
                when (val body = call.decodeTextJsonBody<UpdateRecgovRequest>(roadtripApiJson)) {
                    is RouteBodyResult.Invalid ->
                        return@put call.respondInvalidBody()
                    is RouteBodyResult.Valid -> body.value
                }
            try {
                call.respondEncodedJson(service.save(principal.userId, req))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Store rec.gov credentials")
            .access(RouteAccess.User)

        delete {
            val principal = call.requireUser() ?: return@delete
            try {
                call.respondEncodedJson(service.remove(principal.userId))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Remove rec.gov credentials")
            .access(RouteAccess.User)

        post(LOGIN_PATH) {
            val principal = call.requireUser() ?: return@post
            try {
                call.respondEncodedJson(service.login(principal.userId))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Test rec.gov login with the stored credentials")
            .access(RouteAccess.User)

        post(MFA_PATH) {
            val principal = call.requireUser() ?: return@post
            val req =
                when (val body = call.decodeTextJsonBody<RecgovMfaRequest>(roadtripApiJson)) {
                    is RouteBodyResult.Invalid ->
                        return@post call.respondInvalidBody()
                    is RouteBodyResult.Valid -> body.value
                }
            try {
                call.respondEncodedJson(service.completeMfa(principal.userId, req.code))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Complete a rec.gov MFA challenge")
            .access(RouteAccess.User)

        post(VERIFY_PATH) {
            val principal = call.requireUser() ?: return@post
            try {
                call.respondEncodedJson(service.verify(principal.userId))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Dry-run check of the rec.gov session")
            .access(RouteAccess.User)

        get(STATUS_PATH) {
            val principal: Principal.User = call.requireUser() ?: return@get
            try {
                call.respondEncodedJson(service.status(principal.userId))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Stored rec.gov credentials and live session state")
            .access(RouteAccess.User)
    }
}
