package ca.floo.roadtrip.client.companion

import ca.floo.roadtrip.config.RecGovAtcConfig
import ca.floo.roadtrip.service.booking.RecGovAtcExecutor
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.CancellationException
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
import java.time.Duration

// ── Companion routes ─────────────────────────────────────────────────────────
private const val ATC_PATH = "/atc"
private const val LOGIN_PATH = "/login"
private const val LOGOUT_PATH = "/logout"
private const val DESTROY_PATH = "/destroy"
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
private const val FIELD_LOGIN_STATUS = "login_status"
private const val FIELD_STATE = "state"
private const val FIELD_CART_ADDED = "cart_added"

/** The companion's word for a profile it has never been asked about. */
private const val STATUS_UNCHECKED = "unchecked"
private const val FIELD_HAS_STORED_SESSION = "has_stored_session"
private const val FIELD_RECGOV_AUTH = "recgov_auth"
private const val FIELD_VERIFY = "verify"
private const val FIELD_DIAGNOSTICS = "diagnostics"
private const val FIELD_TRACE = "trace"

/** Short by design: the operator page carries the filenames. */
private const val DIAGNOSTICS_CAPTURED_NOTE = "diagnostics captured"

// ── Companion-internal codes this adapter translates ─────────────────────────
private const val COMPANION_LOGIN_FAILED = "recgov_login_failed"
private const val COMPANION_INVALID_RESPONSE = "companion_invalid_response"
private const val COMPANION_REQUEST_FAILED = "companion_request_failed"
private const val CART_NOT_ADDED = "cart_not_added"

private const val CONTENT_TYPE_JSON = "application/json"
private const val HEADER_ACCEPT = "Accept"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val HEADER_COMPANION_TOKEN = "X-Companion-Token"
private const val MAX_ERROR_BODY_CHARS = 500

private val successStatusRange = 200..299

/**
 * Every route the backend calls on the companion, over one transport.
 *
 * The ATC fire path used to have a client of its own, which re-implemented this
 * one's request builder, token header, JSON reader and success range — and
 * drifted, so a bug fixed here stayed alive there. There is one companion, one
 * shared secret and one set of connection settings, so there is one client.
 *
 * **Nothing throws** except cancellation. Every failure — transport, malformed
 * body, an upstream refusal — comes back as a typed result, because the settings
 * status row must answer even when the companion is down. Companion-internal
 * blockers (which ride in `recgov_auth.reason`) are translated here into the
 * small vocabulary in [RecGovSessionCodes]; no vendor shape crosses the port.
 */
internal class CompanionSessionClient(
    config: RecGovAtcConfig,
    private val httpClient: HttpClient = defaultClient(),
) : CompanionSessionPort,
    RecGovAtcExecutor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = requireNotNull(config.companionBaseUrl) { "companion base URL is required" }.trimEnd('/')
    private val timeout = config.companionTimeout

    /** The shorter budget the pre-hold checks run under; see [RecGovAtcConfig.fireTimeout]. */
    private val fireTimeout = config.fireTimeout
    private val apiToken = config.companionApiToken

    /**
     * The hold itself: one POST that drives a real browser, on the full budget.
     *
     * Session readiness is **not** checked here. The per-profile preflight lives
     * one layer up in `RecGovBookingAdapter`, which is where credential custody
     * is reachable and so where an expired session can actually be recovered.
     * `cart_added` is the only evidence of a hold; `ok` alone is not.
     */
    override suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome {
        log.info("recgov companion ATC POST {}{}", baseUrl, ATC_PATH)
        return when (val exchange = post(ATC_PATH, payload)) {
            is Exchange.Unreachable -> RecGovAtcOutcome.Failed(COMPANION_REQUEST_FAILED, exchange.detail)
            is Exchange.Answered ->
                if (exchange.succeeded && exchange.body.booleanValue(FIELD_CART_ADDED) == true) {
                    RecGovAtcOutcome.Completed(exchange.body)
                } else {
                    RecGovAtcOutcome.Failed(
                        error = exchange.body.stringValue(FIELD_ERROR) ?: "${CART_NOT_ADDED}_http_${exchange.status}",
                        detail = exchange.body.stringValue(FIELD_DETAIL),
                        response = exchange.body,
                    )
                }
        }
    }

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
            // An unattended login is the fire path's one recovery attempt, so
            // it runs on the short budget. An interactive one is a person
            // waiting at a browser and gets the full companion timeout.
            timeout = if (unattended) fireTimeout else timeout,
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

    override suspend fun destroyProfile(profileId: String): CompanionActionResult = actionResult(post(DESTROY_PATH, profileBody(profileId)))

    /**
     * The refresh acquires the profile lock and drives a browser, so on the fire
     * path it takes the short budget like the health check and the unattended
     * login. Left on the companion timeout it can hold the availability poll
     * executor for minutes on a wedged profile. The keepalive sweep is not on
     * anyone's critical path and keeps the full budget.
     */
    override suspend fun refresh(
        profileId: String,
        unattended: Boolean,
    ): CompanionActionResult =
        actionResult(
            post(
                REFRESH_PATH,
                profileBody(profileId),
                timeout = if (unattended) fireTimeout else timeout,
            ),
        )

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
        // Lock-free and cheap on the companion side, and both callers — the ATC
        // preflight and the settings status row — would rather hear
        // "unavailable" quickly than block.
        return when (val exchange = get(HEALTH_PATH, query, timeout = fireTimeout)) {
            is Exchange.Unreachable -> CompanionSessionHealth.Unavailable(exchange.detail)
            is Exchange.Answered -> {
                if (!exchange.succeeded) return CompanionSessionHealth.Unavailable(exchange.body.stringValue(FIELD_ERROR))
                sessionHealth(exchange.body.objectValue(FIELD_RECGOV_AUTH))
            }
        }
    }

    /**
     * The companion's per-profile auth record, as three distinct not-active
     * answers rather than one.
     *
     * `login_status: "unchecked"` is the companion's word for "this profile has
     * never been asked" — a fresh profile, not a lapsed session. And an auth
     * check that *threw* reports its own exception code; telling that user their
     * session expired sends them to re-login against a service that is broken.
     */
    private fun sessionHealth(auth: JsonObject?): CompanionSessionHealth {
        if (auth?.booleanValue(FIELD_LOGGED_IN) == true) return CompanionSessionHealth.Active
        val code = auth?.stringValue(FIELD_ERROR)
        val status = auth?.stringValue(FIELD_LOGIN_STATUS) ?: auth?.stringValue(FIELD_STATE)
        return when {
            code == RecGovSessionCodes.AUTH_CHECK_EXCEPTION -> CompanionSessionHealth.CheckFailed(code)
            // Only when the companion has genuinely never looked. An explicit
            // `logged_in: false` IS an answer, even with no status beside it.
            //
            // `unchecked` alone is not that evidence: the per-profile status is
            // process memory, so every companion restart answers `unchecked` for
            // every profile. A persisted cookie jar says a session did exist, and
            // reading that as NeverLoggedIn drops the user out of the keep-warm
            // sweep on every deploy and tells them "Not logged in yet".
            auth == null -> CompanionSessionHealth.NeverLoggedIn
            status == STATUS_UNCHECKED ->
                if (auth.booleanValue(FIELD_HAS_STORED_SESSION) == true) {
                    CompanionSessionHealth.Inactive(code)
                } else {
                    CompanionSessionHealth.NeverLoggedIn
                }
            else -> CompanionSessionHealth.Inactive(code)
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Both login phases post to `/login` and read the same answer shape. */
    private suspend fun loginExchange(
        profileId: String,
        body: JsonObject,
        timeout: Duration = this.timeout,
    ): CompanionLoginResult =
        when (val exchange = post(LOGIN_PATH, body, timeout)) {
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

    /**
     * The failure's own words, plus a note when the companion kept artifacts.
     *
     * Only a note: the filenames are long, the settings line is one row, and
     * the operator page lists them in full. What the user needs to know is that
     * there is something to look at.
     */
    private fun failureDetail(body: JsonObject): String? {
        val detail = body.stringValue(FIELD_DETAIL) ?: body.objectValue(FIELD_RECGOV_AUTH)?.stringValue(FIELD_DETAIL)
        if (body.objectValue(FIELD_DIAGNOSTICS)?.stringValue(FIELD_TRACE) == null) return detail
        return listOfNotNull(detail, DIAGNOSTICS_CAPTURED_NOTE).joinToString(" — ")
    }

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
        timeout: Duration = this.timeout,
    ): Exchange =
        send(
            requestBuilder(path, query = null, timeout)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
        )

    private suspend fun get(
        path: String,
        query: String?,
        timeout: Duration = this.timeout,
    ): Exchange = send(requestBuilder(path, query, timeout).GET().build())

    private fun requestBuilder(
        path: String,
        query: String?,
        timeout: Duration,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("$baseUrl$path" + if (query == null) "" else "?$query"))
            .timeout(timeout)
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .apply { apiToken?.let { header(HEADER_COMPANION_TOKEN, it) } }

    /**
     * The one place a companion call can fail in transit.
     *
     * The cancellation arm is the whole point: a bare `catch (e: Exception)`
     * swallowed [CancellationException], so a shutdown or a cancelled poll run
     * read as an unreachable companion — the keepalive sweep bumped its
     * `unavailable` metric once per profile, and a cancelled fire was emailed to
     * its owner as a companion failure.
     *
     * Everything *else* is still caught, deliberately. Narrowing to
     * [java.io.IOException] would let a `RejectedExecutionException` from a
     * closing executor escape into the settings status row, which catches only
     * `SettingsError` and would answer 500 on the one read that has to degrade.
     * The order is the contract, as in the availability adapters'
     * `mapUpstreamErrors`: cancellation is never an upstream failure.
     */
    private suspend fun send(request: HttpRequest): Exchange {
        val response =
            try {
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: CancellationException) {
                throw e
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

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultConnectTimeout: Duration = Duration.ofSeconds(10)

        /**
         * One client for every companion caller.
         *
         * Each `java.net.http.HttpClient` owns a selector thread and its own
         * connection pool, and there is exactly one companion behind all of
         * them. Built lazily so a deployment with no companion never allocates
         * one; tests inject their own.
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
