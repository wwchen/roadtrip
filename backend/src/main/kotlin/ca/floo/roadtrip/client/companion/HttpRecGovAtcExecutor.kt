package ca.floo.roadtrip.client.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.booking.RecGovAtcExecutor
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val RECGOV_ATC_PATH = "/atc"
private const val CONTENT_TYPE_JSON = "application/json"
private const val HEADER_ACCEPT = "Accept"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val HEADER_COMPANION_TOKEN = "X-Companion-Token"
private const val ERROR_COMPANION_REQUEST_FAILED = "companion_request_failed"
private const val ERROR_COMPANION_INVALID_RESPONSE = "companion_invalid_response"
private const val ERROR_CART_NOT_ADDED = "cart_not_added"
private const val MAX_ERROR_BODY_CHARS = 500

/**
 * `POST /atc`, and nothing else.
 *
 * Session readiness is **not** checked here. It used to be, against a
 * companion-wide `GET /health` with its own answer-shape reader, which both
 * duplicated [CompanionSessionClient.health] and — once every profile became a
 * user's own — asked about the wrong session entirely. The per-profile
 * preflight now lives one layer up, in `RecGovBookingAdapter`, which is also
 * where an expired session can be recovered because that is where credential
 * custody is reachable. One health authority, one place that decides whether to
 * drive the browser.
 */
internal class HttpRecGovAtcExecutor(
    config: RecGovAtcConfig,
    private val httpClient: HttpClient = defaultClient(),
) : RecGovAtcExecutor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = requireNotNull(config.companionBaseUrl) { "recgov ATC companion base URL is required" }
    private val timeout = config.companionTimeout
    private val apiToken = config.companionApiToken

    override suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome {
        val endpoint = URI.create("${baseUrl.trimEnd('/')}$RECGOV_ATC_PATH")
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(timeout)
                .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                // The companion requires the shared secret on every route.
                .apply { apiToken?.let { header(HEADER_COMPANION_TOKEN, it) } }
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

    companion object {
        private val successStatusRange = 200..299
        private val defaultConnectTimeout: Duration = Duration.ofSeconds(10)
        private val json =
            Json {
                ignoreUnknownKeys = true
            }

        /**
         * One client for every companion caller.
         *
         * Each `java.net.http.HttpClient` owns a selector thread and its own
         * connection pool, and there is exactly one companion behind all of
         * them — so a fresh client per caller buys nothing and costs a thread
         * plus a cold connection. Built lazily so a deployment with no
         * companion never allocates one; tests inject their own.
         */
        private val sharedClient: HttpClient by lazy {
            HttpClient
                .newBuilder()
                .connectTimeout(defaultConnectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        }

        fun defaultClient(): HttpClient = sharedClient
    }
}
