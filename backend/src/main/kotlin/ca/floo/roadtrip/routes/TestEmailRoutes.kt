package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
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

private const val MAX_TEST_EMAIL_TO_CHARS = 320
private val emailRecipientPattern = Regex("""^[^\s@]+@[^\s@]+$""")

@OptIn(ExperimentalSerializationApi::class)
private val testEmailJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

/**
 * Admin-only email smoke-test endpoint. Registered on the admin server, not the
 * public app, because it causes outbound email side effects.
 */
internal fun Route.testEmailRoutes(email: EmailNotificationService) {
    post("/test/email", {
        tags = listOf("test")
        summary = "Send a test email"
        request {
            body<TestEmailRequest> {
                mediaTypes(ContentType.Application.Json)
                example("recipient") {
                    value = TestEmailRequest(to = "camper@example.com")
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                body<TestEmailResponse> {
                    mediaTypes(ContentType.Application.Json)
                    example("sent") {
                        value = TestEmailResponse(sent = true, to = "camper@example.com")
                    }
                }
            }
            code(HttpStatusCode.BadRequest) {
                body<ApiErrorSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("missing recipient") {
                        value = ApiErrorSchema(error = "missing_to", detail = "Expected JSON body: {\"to\":\"person@example.com\"}.")
                    }
                    example("invalid recipient") {
                        value = ApiErrorSchema(error = "invalid_to", detail = "Recipient address must look like an email address.")
                    }
                }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                body<ApiErrorSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("email disabled") {
                        value =
                            ApiErrorSchema(
                                error = "email_send_failed",
                                detail = "Email is not configured or Resend rejected the request.",
                            )
                    }
                }
            }
        }
    }) {
        val request =
            runCatching { testEmailJson.decodeFromString<TestEmailRequest>(call.receiveText()) }
                .getOrNull()
        val to = request?.to?.trim().orEmpty()
        val validationError = testEmailRecipientError(to)
        if (validationError != null) {
            call.respondTestEmailJson(validationError, HttpStatusCode.BadRequest)
            return@post
        }

        if (!email.sendTestEmail(to)) {
            call.respondTestEmailJson(
                ApiErrorSchema(
                    error = "email_send_failed",
                    detail = "Email is not configured or Resend rejected the request.",
                ),
                HttpStatusCode.ServiceUnavailable,
            )
            return@post
        }

        call.respondTestEmailJson(TestEmailResponse(sent = true, to = to))
    }
}

private fun testEmailRecipientError(to: String): ApiErrorSchema? =
    when {
        to.isBlank() ->
            ApiErrorSchema(error = "missing_to", detail = "Expected JSON body: {\"to\":\"person@example.com\"}.")
        to.length > MAX_TEST_EMAIL_TO_CHARS ->
            ApiErrorSchema(error = "invalid_to", detail = "Recipient address is too long.")
        !emailRecipientPattern.matches(to) ->
            ApiErrorSchema(error = "invalid_to", detail = "Recipient address must look like an email address.")
        else -> null
    }

@Serializable
private data class TestEmailRequest(
    val to: String? = null,
)

@Serializable
private data class TestEmailResponse(
    val sent: Boolean,
    val to: String,
)

private suspend inline fun <reified T> ApplicationCall.respondTestEmailJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(testEmailJson.encodeToString(value), ContentType.Application.Json, status)
