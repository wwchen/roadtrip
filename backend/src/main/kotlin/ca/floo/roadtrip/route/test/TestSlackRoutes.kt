package ca.floo.roadtrip.route.test

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_TEST_SLACK_CHANNEL_CHARS = 255

@OptIn(ExperimentalSerializationApi::class)
private val testSlackJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

/** Slack smoke-test endpoint for verifying the configured bot token/channel. */
internal fun Route.testSlackRoutes(slack: SlackNotificationService) {
    route("/test") {
        post("/slack") {
            val rawBody = call.receiveText()
            val request =
                if (rawBody.isBlank()) {
                    TestSlackRequest()
                } else {
                    runCatching { testSlackJson.decodeFromString<TestSlackRequest>(rawBody) }.getOrNull()
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
) = respondText(testSlackJson.encodeToString(value), ContentType.Application.Json, status)
