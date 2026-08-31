package ca.floo.roadtrip.client.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.booking.RecGovAtcExecutor
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val RECGOV_ATC_PATH = "/atc"
private const val RECGOV_HEALTH_PATH = "/health"
private const val CONTENT_TYPE_JSON = "application/json"
private const val HEADER_ACCEPT = "Accept"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val HEADER_COMPANION_TOKEN = "X-Companion-Token"
private const val ERROR_COMPANION_REQUEST_FAILED = "companion_request_failed"
private const val ERROR_COMPANION_INVALID_RESPONSE = "companion_invalid_response"
private const val ERROR_COMPANION_HEALTH_REQUEST_FAILED = "companion_health_request_failed"
private const val ERROR_COMPANION_HEALTH_INVALID_RESPONSE = "companion_health_invalid_response"
private const val ERROR_COMPANION_HEALTH_HTTP = "companion_health_http_error"
private const val ERROR_COMPANION_HEALTH_NOT_OK = "companion_health_not_ok"
private const val ERROR_CART_NOT_ADDED = "cart_not_added"
private const val MAX_ERROR_BODY_CHARS = 500
private const val HEALTH_STATUS_OK = "ok"

internal class HttpRecGovAtcExecutor(
    config: RecGovAtcConfig,
    private val httpClient: HttpClient = defaultClient(),
) : RecGovAtcExecutor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = requireNotNull(config.companionBaseUrl) { "recgov ATC companion base URL is required" }
    private val timeout = config.companionTimeout
    private val apiToken = config.companionApiToken

    // The companion requires the shared secret on every route it serves.
    private fun HttpRequest.Builder.withCompanionAuth(): HttpRequest.Builder =
        apply { apiToken?.let { header(HEADER_COMPANION_TOKEN, it) } }

    override suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome {
        preflightCompanion()?.let { return it }

        val endpoint = URI.create("${baseUrl.trimEnd('/')}$RECGOV_ATC_PATH")
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(timeout)
                .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .withCompanionAuth()
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()

        log.info("recgov companion ATC POST {}", endpoint)
        val response =
            try {
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                return RecGovAtcOutcome.Failed(
                    error = ERROR_COMPANION_REQUEST_FAILED,
                    detail = e.message,
                )
            }

        val body = response.body().orEmpty()
        val parsed =
            runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrNull()
                ?: return RecGovAtcOutcome.Failed(
                    error = ERROR_COMPANION_INVALID_RESPONSE,
                    detail = body.take(MAX_ERROR_BODY_CHARS),
                )

        val success =
            response.statusCode() in successStatusRange &&
                parsed.booleanValue("ok") == true &&
                parsed.booleanValue("cart_added") == true
        if (success) return RecGovAtcOutcome.Completed(response = parsed)

        return RecGovAtcOutcome.Failed(
            error = parsed.stringValue("error") ?: "${ERROR_CART_NOT_ADDED}_http_${response.statusCode()}",
            detail = parsed.stringValue("detail"),
            response = parsed,
        )
    }

    private suspend fun preflightCompanion(): RecGovAtcOutcome.Failed? {
        val endpoint = URI.create("${baseUrl.trimEnd('/')}$RECGOV_HEALTH_PATH")
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(timeout)
                .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                .withCompanionAuth()
                .GET()
                .build()

        log.info("recgov companion health GET {}", endpoint)
        val response =
            try {
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                return healthPreflightFailed(
                    error = ERROR_COMPANION_HEALTH_REQUEST_FAILED,
                    detail = e.message,
                    response = null,
                )
            }

        val body = response.body().orEmpty()
        val parsed =
            runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrNull()
                ?: return healthPreflightFailed(
                    error = ERROR_COMPANION_HEALTH_INVALID_RESPONSE,
                    detail = body.take(MAX_ERROR_BODY_CHARS),
                    response = null,
                )

        if (response.statusCode() !in successStatusRange) {
            return companionHealthFailed(
                parsed,
                fallbackError = ERROR_COMPANION_HEALTH_HTTP,
                fallbackDetail = body.take(MAX_ERROR_BODY_CHARS),
            )
        }
        if (parsed.booleanValue("busy") == true) {
            return companionHealthFailed(parsed, fallbackError = ERROR_COMPANION_HEALTH_NOT_OK)
        }
        if (parsed.booleanValue("ok") != true) {
            return companionHealthFailed(parsed, fallbackError = ERROR_COMPANION_HEALTH_NOT_OK)
        }
        if (!parsed.recgovAuthOk()) {
            return companionHealthFailed(parsed, fallbackError = ERROR_COMPANION_HEALTH_NOT_OK)
        }
        return null
    }

    private fun companionHealthFailed(
        response: JsonObject,
        fallbackError: String,
        fallbackDetail: String? = null,
    ): RecGovAtcOutcome.Failed =
        healthPreflightFailed(
            error = response.companionError() ?: fallbackError,
            detail = response.companionDetail() ?: fallbackDetail,
            response = response,
        )

    private fun healthPreflightFailed(
        error: String,
        detail: String?,
        response: JsonObject?,
    ): RecGovAtcOutcome.Failed {
        log.warn("recgov companion health preflight failed error={} detail={}", error, detail)
        return RecGovAtcOutcome.Failed(
            error = error,
            detail = detail,
            response = response,
        )
    }

    companion object {
        private val successStatusRange = 200..299
        private val defaultConnectTimeout: Duration = Duration.ofSeconds(10)
        private val json =
            Json {
                ignoreUnknownKeys = true
            }

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(defaultConnectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }
}

private fun JsonObject.stringValue(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.booleanValue(name: String): Boolean? = get(name)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.objectValue(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.recgovAuthOk(): Boolean {
    val auth = objectValue("recgov_auth") ?: return false
    return auth.stringValue("login_status") == HEALTH_STATUS_OK ||
        auth.stringValue("state") == HEALTH_STATUS_OK ||
        auth.booleanValue("logged_in") == true
}

private fun JsonObject.companionError(): String? =
    stringValue("error")
        ?: objectValue("recgov_auth")?.let { auth ->
            auth.stringValue("error")
                ?: auth.stringValue("state")?.takeUnless { it == HEALTH_STATUS_OK }
                ?: auth.stringValue("login_status")?.takeUnless { it == HEALTH_STATUS_OK }
        }

private fun JsonObject.companionDetail(): String? =
    stringValue("detail")
        ?: get("diagnostics")?.toString()
        ?: objectValue("recgov_auth")?.let { auth ->
            auth.stringValue("detail")
                ?: auth.stringValue("corrective_action")
        }
