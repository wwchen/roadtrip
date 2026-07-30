package ca.floo.roadtrip.route.test

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.decodeOptionalTextJsonBody
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_TEST_SLACK_CHANNEL_CHARS = 255

@OptIn(ExperimentalSerializationApi::class)
private val testSlackJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

/**
 * Slack smoke-test endpoint for verifying the configured bot token/channel.
 *
 * Signed-in only: it posts to a caller-supplied channel on the deployment's bot
 * token, so an anonymous caller could spray any channel the bot can reach.
 */
internal fun Route.testSlackRoutes(slack: SlackNotificationService) {
    route("/test") {
        post("/slack") {
            val request =
                when (
                    val body =
                        call.decodeOptionalTextJsonBody(
                            json = testSlackJson,
                            default = ::TestSlackRequest,
                        )
                ) {
                    is RouteBodyResult.Invalid -> null
                    is RouteBodyResult.Valid -> body.value
                }
            if (request == null) {
                call.respondTestSlackJson(
                    ApiErrorSchema(error = "invalid_body", detail = "Expected JSON body: {\"channel\":\"#camping\"}."),
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            val channel = request.channel?.trim()?.takeIf { it.isNotEmpty() }
            if (channel != null && channel.length > MAX_TEST_SLACK_CHANNEL_CHARS) {
                call.respondTestSlackJson(
                    ApiErrorSchema(error = "invalid_channel", detail = "Channel override is too long."),
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

            if (!slack.sendTestMessage(channel)) {
                call.respondTestSlackJson(
                    ApiErrorSchema(
                        error = "slack_send_failed",
                        detail = "Slack is not configured or Slack rejected the request.",
                    ),
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }

            call.respondTestSlackJson(TestSlackResponse(sent = true, channel = channel))
        }.describeApi("test", "Send a test Slack message")
            .access(RouteAccess.User)
    }
}

@Serializable
private data class TestSlackRequest(
    val channel: String? = null,
)

@Serializable
private data class TestSlackResponse(
    val sent: Boolean,
    val channel: String? = null,
)

private suspend inline fun <reified T> ApplicationCall.respondTestSlackJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondEncodedJson(testSlackJson, value, status)
