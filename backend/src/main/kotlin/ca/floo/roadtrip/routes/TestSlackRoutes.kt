package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
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
    post("/test/slack", {
        tags = listOf("test")
        summary = "Send a test Slack message"
        request {
            body<TestSlackRequest> {
                mediaTypes(ContentType.Application.Json)
                example("default channel") {
                    value = TestSlackRequest()
                }
                example("channel override") {
                    value = TestSlackRequest(channel = "#camping")
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                body<TestSlackResponse> {
                    mediaTypes(ContentType.Application.Json)
                    example("sent") {
                        value = TestSlackResponse(sent = true, channel = "#camping")
                    }
                }
            }
            code(HttpStatusCode.BadRequest) {
                body<ApiErrorSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("invalid body") {
                        value = ApiErrorSchema(error = "invalid_body", detail = "Expected JSON body: {\"channel\":\"#camping\"}.")
                    }
                    example("invalid channel") {
                        value = ApiErrorSchema(error = "invalid_channel", detail = "Channel override is too long.")
                    }
                }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                body<ApiErrorSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("slack disabled") {
                        value =
                            ApiErrorSchema(
                                error = "slack_send_failed",
                                detail = "Slack is not configured or Slack rejected the request.",
                            )
                    }
                }
            }
        }
    }) {
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
