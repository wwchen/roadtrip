package ca.floo.roadtrip.client.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

// ── Companion routes ─────────────────────────────────────────────────────────
private const val LOGIN_PATH = "/login"
private const val LOGOUT_PATH = "/logout"
private const val VERIFY_PATH = "/verify"
private const val REFRESH_PATH = "/refresh"
private const val KEEP_WARM_PATH = "/keep-warm"
private const val HEALTH_PATH = "/health"

// ── Wire fields ──────────────────────────────────────────────────────────────
private const val FIELD_PROFILE_ID = "profile_id"
private const val FIELD_PROFILE_IDS = "profile_ids"
private const val FIELD_USERNAME = "username"
private const val FIELD_PASSWORD = "password"
private const val FIELD_CHALLENGE_ID = "challenge_id"
private const val FIELD_MFA_CODE = "mfa_code"
private const val FIELD_UNATTENDED = "unattended"
private const val FIELD_EXPIRES_AT = "expires_at"
private const val FIELD_OK = "ok"
private const val FIELD_ERROR = "error"
private const val FIELD_DETAIL = "detail"
private const val FIELD_REASON = "reason"
private const val FIELD_LOGGED_IN = "logged_in"
private const val FIELD_RECGOV_AUTH = "recgov_auth"
private const val FIELD_VERIFY = "verify"

// ── Companion-internal codes this adapter translates ─────────────────────────
private const val COMPANION_LOGIN_FAILED = "recgov_login_failed"
private const val COMPANION_INVALID_RESPONSE = "companion_invalid_response"

private const val CONTENT_TYPE_JSON = "application/json"
private const val HEADER_ACCEPT = "Accept"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val HEADER_COMPANION_TOKEN = "X-Companion-Token"
private const val MAX_ERROR_BODY_CHARS = 500

private val successStatusRange = 200..299

/**
 * The companion's per-profile session routes, as the settings layer sees them.
 *
 * Sits beside [HttpRecGovAtcExecutor] because it is the same service and the
 * same shared secret; it is separate from it because the ATC fire path and the
 * interactive settings flows have nothing to share but transport. Base URL,
 * timeout and token all come from the existing [RecGovAtcConfig] rather than a
 * second copy of the same three settings.
 *
 * **Nothing throws.** Every failure — transport, malformed body, an upstream
 * refusal — comes back as a typed result, because the settings status row must
 * answer even when the companion is down. Companion-internal blockers (which
 * ride in `recgov_auth.reason`) are translated here into the small vocabulary in
 * [RecGovSessionCodes]; no vendor shape crosses the port.
 */
internal class CompanionSessionClient(
    config: RecGovAtcConfig,
    private val httpClient: HttpClient = HttpRecGovAtcExecutor.defaultClient(),
) : CompanionSessionPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = requireNotNull(config.companionBaseUrl) { "companion base URL is required" }.trimEnd('/')
    private val timeout = config.companionTimeout
    private val apiToken = config.companionApiToken

    override suspend fun login(
        profileId: String,
        username: String,
        password: String,
        unattended: Boolean,
    ): CompanionLoginResult =
        loginExchange(
            profileId,
            buildJsonObject {
                put(FIELD_PROFILE_ID, profileId)
                put(FIELD_USERNAME, username)
                put(FIELD_PASSWORD, password)
                if (unattended) put(FIELD_UNATTENDED, true)
            },
        )

    override suspend fun completeMfa(
        profileId: String,
        challengeId: String,
        code: String,
    ): CompanionLoginResult =
        loginExchange(
            profileId,
            buildJsonObject {
                put(FIELD_PROFILE_ID, profileId)
                put(FIELD_CHALLENGE_ID, challengeId)
                put(FIELD_MFA_CODE, code)
            },
        )

    override suspend fun logout(profileId: String): CompanionActionResult = actionResult(post(LOGOUT_PATH, profileBody(profileId)))

    override suspend fun refresh(profileId: String): CompanionActionResult = actionResult(post(REFRESH_PATH, profileBody(profileId)))

    override suspend fun markKeepWarm(profileIds: Collection<String>): CompanionActionResult =
        actionResult(
            post(
                KEEP_WARM_PATH,
                buildJsonObject {
                    put(FIELD_PROFILE_IDS, buildJsonArray { profileIds.forEach { add(JsonPrimitive(it)) } })
                },
            ),
        )

    override suspend fun verify(profileId: String): CompanionActionResult =
        when (val exchange = post(VERIFY_PATH, profileBody(profileId))) {
            is Exchange.Unreachable -> unavailableAction(exchange.detail)
            is Exchange.Answered -> {
                val verify = exchange.body.objectValue(FIELD_VERIFY)
                if (exchange.succeeded && verify?.booleanValue(FIELD_OK) != false) {
                    CompanionActionResult.Ok
                } else {
                    CompanionActionResult.Failed(
                        verify?.stringValue(FIELD_ERROR)
                            ?: exchange.body.stringValue(FIELD_ERROR)
                            ?: RecGovSessionCodes.NOT_AUTHENTICATED,
                        verify?.stringValue(FIELD_DETAIL) ?: exchange.body.stringValue(FIELD_DETAIL),
                    )
                }
            }
        }

    override suspend fun health(profileId: String): CompanionSessionHealth {
        val query = "$FIELD_PROFILE_ID=${URLEncoder.encode(profileId, StandardCharsets.UTF_8)}"
        return when (val exchange = get(HEALTH_PATH, query)) {
            is Exchange.Unreachable -> CompanionSessionHealth.Unavailable(exchange.detail)
            is Exchange.Answered -> {
                if (!exchange.succeeded) return CompanionSessionHealth.Unavailable(exchange.body.stringValue(FIELD_ERROR))
                val auth = exchange.body.objectValue(FIELD_RECGOV_AUTH)
                if (auth?.booleanValue(FIELD_LOGGED_IN) == true) {
                    CompanionSessionHealth.Active
                } else {
                    CompanionSessionHealth.Inactive(auth?.stringValue(FIELD_ERROR))
                }
            }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Both login phases post to `/login` and read the same answer shape. */
    private suspend fun loginExchange(
        profileId: String,
        body: JsonObject,
    ): CompanionLoginResult =
        when (val exchange = post(LOGIN_PATH, body)) {
            is Exchange.Unreachable -> {
                log.warn("companion login unreachable profile={} detail={}", profileId, exchange.detail)
                CompanionLoginResult.Failed(RecGovSessionCodes.COMPANION_UNAVAILABLE, exchange.detail)
            }
            is Exchange.Answered -> {
                val challengeId = exchange.body.stringValue(FIELD_CHALLENGE_ID)
                when {
                    exchange.body.stringValue(FIELD_ERROR) == RecGovSessionCodes.MFA_REQUIRED && challengeId != null ->
                        CompanionLoginResult.MfaRequired(challengeId, exchange.body.stringValue(FIELD_EXPIRES_AT))
                    exchange.loggedIn -> CompanionLoginResult.Ok
                    else -> CompanionLoginResult.Failed(loginFailureCode(exchange.body), failureDetail(exchange.body))
                }
            }
        }

    /**
     * The companion keeps `error` a stable code and puts the actual blocker in
     * `recgov_auth.reason`, so a captcha arrives as `recgov_login_failed` +
     * `reason: captcha_required`. The reason is checked first for exactly that;
     * anything else keeps the companion's own code so operators see the truth.
     */
    private fun loginFailureCode(body: JsonObject): String {
        val auth = body.objectValue(FIELD_RECGOV_AUTH)
        if (auth?.stringValue(FIELD_REASON) == RecGovSessionCodes.CAPTCHA_REQUIRED) {
            return RecGovSessionCodes.CAPTCHA_REQUIRED
        }
        return when (val code = body.stringValue(FIELD_ERROR) ?: auth?.stringValue(FIELD_ERROR)) {
            null, COMPANION_LOGIN_FAILED -> RecGovSessionCodes.LOGIN_FAILED
            else -> code
        }
    }

    private fun failureDetail(body: JsonObject): String? =
        body.stringValue(FIELD_DETAIL) ?: body.objectValue(FIELD_RECGOV_AUTH)?.stringValue(FIELD_DETAIL)

    private fun profileBody(profileId: String): JsonObject = buildJsonObject { put(FIELD_PROFILE_ID, profileId) }

    /** The plain succeeded-or-here-is-why shape the non-login routes answer with. */
    private fun actionResult(exchange: Exchange): CompanionActionResult =
        when (exchange) {
            is Exchange.Unreachable -> unavailableAction(exchange.detail)
            is Exchange.Answered ->
                if (exchange.succeeded) {
                    CompanionActionResult.Ok
                } else {
                    CompanionActionResult.Failed(
                        exchange.body.stringValue(FIELD_ERROR) ?: RecGovSessionCodes.LOGIN_FAILED,
                        exchange.body.stringValue(FIELD_DETAIL),
                    )
                }
        }

    private fun unavailableAction(detail: String?): CompanionActionResult =
        CompanionActionResult.Failed(RecGovSessionCodes.COMPANION_UNAVAILABLE, detail)

    private sealed interface Exchange {
        data class Answered(
            val status: Int,
            val body: JsonObject,
        ) : Exchange {
            val succeeded: Boolean get() = status in successStatusRange && body.booleanValue(FIELD_OK) != false

            /** `ok` alone is not enough for a login: the profile must be signed in. */
            val loggedIn: Boolean get() = succeeded && body.objectValue(FIELD_RECGOV_AUTH)?.booleanValue(FIELD_LOGGED_IN) == true
        }

        data class Unreachable(
            val detail: String?,
        ) : Exchange
    }

    private suspend fun post(
        path: String,
        body: JsonObject,
    ): Exchange =
        send(
            requestBuilder(path, query = null)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
        )

    private suspend fun get(
        path: String,
        query: String?,
    ): Exchange = send(requestBuilder(path, query).GET().build())

    private fun requestBuilder(
        path: String,
        query: String?,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("$baseUrl$path" + if (query == null) "" else "?$query"))
            .timeout(timeout)
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .apply { apiToken?.let { header(HEADER_COMPANION_TOKEN, it) } }

    private suspend fun send(request: HttpRequest): Exchange {
        val response =
            try {
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                return Exchange.Unreachable(e.message)
            }
        val raw = response.body().orEmpty()
        val parsed =
            runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                // A body this layer cannot read is indistinguishable from an
                // absent companion as far as the caller is concerned.
                ?: return Exchange.Unreachable("$COMPANION_INVALID_RESPONSE: ${raw.take(MAX_ERROR_BODY_CHARS)}")
        return Exchange.Answered(response.statusCode(), parsed)
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
