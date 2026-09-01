package ca.floo.roadtrip.route.api.settings

import ca.floo.roadtrip.model.api.SettingsResponseDto
import ca.floo.roadtrip.model.api.UpdateNotificationsRequest
import ca.floo.roadtrip.model.api.UpdateProfileRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.decodeOptionalTextJsonBody
import ca.floo.roadtrip.route.common.decodeTextJsonBody
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.settings.SettingsError
import ca.floo.roadtrip.service.settings.UserSettingsPort
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// ── Path segments ────────────────────────────────────────────────────────────
private const val SEGMENT_PROFILE = "/profile"
private const val SEGMENT_NOTIFICATIONS = "/notifications"
private const val SLACK_PATH = "/notifications/slack"
private const val SLACK_TEST_PATH = "/notifications/slack/test"
private const val EMAIL_TEST_PATH = "/notifications/email/test"

/** Body for `POST /api/settings/notifications/slack/test`. */
@Serializable
private data class SlackTestRequest(
    val channel: String? = null,
)

/**
 * Account settings HTTP shell.
 *
 * All routes require an authenticated [Principal.User]. Business logic lives in
 * [UserSettingsPort]; this layer only parses inputs, calls the service, maps
 * [SettingsError] to status codes, and serializes DTOs.
 */
internal fun Route.settingsRoutes(service: UserSettingsPort) {
    route(API_SETTINGS) {
        get {
            val principal = call.requireUser() ?: return@get
            val dto = service.read(principal)
            call.respondSettings(dto)
        }.describeApi(TAG_SETTINGS, "Get current account settings")
            .access(RouteAccess.User)

        put(SEGMENT_PROFILE) {
            val principal = call.requireUser() ?: return@put
            val req =
                when (val body = call.decodeTextJsonBody<UpdateProfileRequest>(roadtripApiJson)) {
                    is RouteBodyResult.Invalid ->
                        return@put call.respondInvalidBody()
                    is RouteBodyResult.Valid -> body.value
                }
            try {
                call.respondSettings(service.updateProfile(principal.userId, req))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Update profile settings")
            .access(RouteAccess.User)

        put(SEGMENT_NOTIFICATIONS) {
            val principal = call.requireUser() ?: return@put
            val req =
                when (
                    val body =
                        call.decodeOptionalTextJsonBody<UpdateNotificationsRequest>(roadtripApiJson) {
                            UpdateNotificationsRequest()
                        }
                ) {
                    is RouteBodyResult.Invalid ->
                        return@put call.respondInvalidBody()
                    is RouteBodyResult.Valid -> body.value
                }
            try {
                call.respondSettings(service.updateNotifications(principal, req))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Update notification settings")
            .access(RouteAccess.User)

        delete(SLACK_PATH) {
            val principal = call.requireUser() ?: return@delete
            val dto = service.disconnectSlack(principal.userId)
            call.respondSettings(dto)
        }.describeApi(TAG_SETTINGS, "Disconnect Slack integration")
            .access(RouteAccess.User)

        post(SLACK_TEST_PATH) {
            val principal = call.requireUser() ?: return@post
            val req =
                when (
                    val body =
                        call.decodeOptionalTextJsonBody<SlackTestRequest>(roadtripApiJson) { SlackTestRequest() }
                ) {
                    is RouteBodyResult.Invalid ->
                        return@post call.respondInvalidBody()
                    is RouteBodyResult.Valid -> body.value
                }
            try {
                call.respondEncodedJson(service.sendSlackTest(principal.userId, req.channel))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Send a Slack test message")
            .access(RouteAccess.User)

        post(EMAIL_TEST_PATH) {
            val principal = call.requireUser() ?: return@post
            try {
                call.respondEncodedJson(service.sendEmailTest(principal.userId))
            } catch (e: SettingsError) {
                call.respondSettingsError(e)
            }
        }.describeApi(TAG_SETTINGS, "Send a test email")
            .access(RouteAccess.User)
    }
}

private suspend fun ApplicationCall.respondSettings(dto: SettingsResponseDto) = respondEncodedJson(dto)
