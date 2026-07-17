package ca.floo.roadtrip.route.test

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.decodeTextJsonBody
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
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

/** Email smoke-test endpoint for verifying the configured Resend sender. */
internal fun Route.testEmailRoutes(
    email: EmailNotificationService,
    appRootUrl: String? = null,
) {
    route("/test") {
        post("/email") {
            val request =
                when (val body = call.decodeTextJsonBody<TestEmailRequest>(testEmailJson)) {
                    is RouteBodyResult.Invalid -> null
                    is RouteBodyResult.Valid -> body.value
                }
            val to = request?.to?.trim().orEmpty()
            val validationError = testEmailRecipientError(to)
            if (validationError != null) {
                call.respondTestEmailJson(validationError, HttpStatusCode.BadRequest)
                return@post
            }

            val recipients = parseRecipients(to)
            if (!email.sendTestEmail(recipients, appRootUrl)) {
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
        }.describeApi("test", "Send a test email")
    }
}

private fun parseRecipients(to: String): List<String> = to.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun testEmailRecipientError(to: String): ApiErrorSchema? =
    when {
        to.isBlank() ->
            ApiErrorSchema(error = "missing_to", detail = "Expected JSON body: {\"to\":\"person@example.com\"}.")
        to.length > MAX_TEST_EMAIL_TO_CHARS ->
            ApiErrorSchema(error = "invalid_to", detail = "Recipient address is too long.")
        parseRecipients(to).isEmpty() || parseRecipients(to).any { !emailRecipientPattern.matches(it) } ->
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
) = respondEncodedJson(testEmailJson, value, status)
