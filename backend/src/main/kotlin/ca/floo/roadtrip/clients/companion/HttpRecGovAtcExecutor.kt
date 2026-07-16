package ca.floo.roadtrip.clients.companion

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

private const val RECGOV_ATC_PATH = "/recgov/atc"
private const val CONTENT_TYPE_JSON = "application/json"
private const val HEADER_ACCEPT = "Accept"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val ERROR_COMPANION_REQUEST_FAILED = "companion_request_failed"
private const val ERROR_COMPANION_INVALID_RESPONSE = "companion_invalid_response"
private const val ERROR_CART_NOT_ADDED = "cart_not_added"
private const val MAX_ERROR_BODY_CHARS = 500

internal class HttpRecGovAtcExecutor(
    config: RecGovAtcConfig,
    private val client: HttpClient = defaultClient(),
) : RecGovAtcExecutor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = requireNotNull(config.companionBaseUrl) { "recgov ATC companion base URL is required" }
    private val timeout = config.companionTimeout

    override suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome {
        val endpoint = URI.create("${baseUrl.trimEnd('/')}$RECGOV_ATC_PATH")
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(timeout)
                .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()

        log.info("recgov companion ATC POST {}", endpoint)
        val response =
            try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
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
            response.statusCode() in SUCCESS_STATUS_RANGE &&
                parsed.booleanValue("ok") == true &&
                parsed.booleanValue("cart_added") == true
        if (success) return RecGovAtcOutcome.Completed(response = parsed)

        return RecGovAtcOutcome.Failed(
            error = parsed.stringValue("error") ?: "${ERROR_CART_NOT_ADDED}_http_${response.statusCode()}",
            detail = parsed.stringValue("detail"),
            response = parsed,
        )
    }

    companion object {
        private val SUCCESS_STATUS_RANGE = 200..299
        private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val json =
            Json {
                ignoreUnknownKeys = true
            }

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }
}

private fun JsonObject.stringValue(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.booleanValue(name: String): Boolean? = get(name)?.jsonPrimitive?.booleanOrNull
