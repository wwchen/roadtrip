package ca.floo.campsite.recgov.booker.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64

// Pure JVM port of companion/src/auth.js. Browser-free recreation.gov auth:
// the user pastes localStorage.getItem('recaccount') (or a curl trace), we
// extract account_id + refresh_id + access_token, then mint fresh JWTs via
// the refresh endpoint. No Akamai-gated browser path needed.

private val log = LoggerFactory.getLogger("RecgovAuth")

private val curlCookieRe = Regex("""(?:-b|--cookie)\s+['"]([^'"]*)['"]""")
private val curlCookieHeaderRe = Regex("""-H\s+['"][Cc]ookie:\s*([^'"]*)['"]""")
private val curlBearerRe = Regex("""-H\s+['"][Aa]uthorization:\s+[Bb]earer\s+([A-Za-z0-9._-]+)""")

// curl data quoting: macOS/zsh "Copy as cURL" wraps the JSON body in single
// quotes, e.g. --data-raw '{"a":"b"}'. Match the JSON body greedily up to the
// trailing quote — we do not try to handle escaped quotes inside the body
// because rec.gov refresh payloads only contain ASCII ids.
private val curlRawDataSingleRe = Regex("""--data-raw\s+'(\{[^']*\})'""")
private val curlRawDataDoubleRe = Regex("""--data-raw\s+"(\{[^"]*\})"""")

data class RefreshCreds(
    val accountId: String,
    val refreshId: String,
) {
    fun toJson(): String = """{"account_id":"$accountId","refresh_id":"$refreshId"}"""
}

data class TokenInfo(
    val expires: Instant?,
    val expired: Boolean,
    val fingerprint: String,
)

object RecgovAuth {
    fun extractCookies(input: String): String {
        val s = input.trim()
        if (!s.startsWith("curl ")) return s
        curlCookieRe.find(s)?.let { return it.groupValues[1].trim() }
        curlCookieHeaderRe.find(s)?.let { return it.groupValues[1].trim() }
        return s
    }

    fun countCookies(cookieString: String): Int = cookieString.split(';').count { it.contains('=') }

    fun extractBearer(input: String): String? = curlBearerRe.find(input)?.groupValues?.get(1)

    fun extractRefreshCreds(input: String): RefreshCreds? {
        val s = input.trim()

        // Form 1: --data-raw '{...}' or "{...}" inside a curl command
        val rawMatch = curlRawDataSingleRe.find(s) ?: curlRawDataDoubleRe.find(s)
        if (rawMatch != null) {
            runCatching {
                val obj = Json.parseToJsonElement(rawMatch.groupValues[1]).jsonObject
                val accountId = obj["account_id"]?.jsonPrimitiveContent()
                val refreshId = obj["refresh_id"]?.jsonPrimitiveContent()
                if (!accountId.isNullOrEmpty() && !refreshId.isNullOrEmpty()) {
                    return RefreshCreds(accountId, refreshId)
                }
            }
        }

        // Form 2/3: raw JSON, either flat {account_id, refresh_id} or
        // a recaccount-shaped {account: {account_id}, refresh_id}
        runCatching {
            val obj = Json.parseToJsonElement(s).jsonObject
            val refreshId = obj["refresh_id"]?.jsonPrimitiveContent()
            val flatAccountId = obj["account_id"]?.jsonPrimitiveContent()
            val nestedAccountId = (obj["account"] as? JsonObject)?.get("account_id")?.jsonPrimitiveContent()
            val accountId = flatAccountId ?: nestedAccountId
            if (!accountId.isNullOrEmpty() && !refreshId.isNullOrEmpty()) {
                return RefreshCreds(accountId, refreshId)
            }
        }
        return null
    }

    /** Try to read a recaccount-shaped object from input. Returns access_token if present. */
    fun extractAccessTokenFromRecaccount(input: String): String? {
        val s = input.trim()
        return runCatching {
            (Json.parseToJsonElement(s) as? JsonObject)
                ?.get("access_token")
                ?.jsonPrimitiveContent()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun decodeJwt(token: String): JsonObject? =
        runCatching {
            val parts = token.split('.')
            if (parts.size < 2) return@runCatching null
            val payload = String(Base64.getUrlDecoder().decode(padBase64Url(parts[1])))
            Json.parseToJsonElement(payload) as? JsonObject
        }.getOrNull()

    fun tokenInfo(token: String?): TokenInfo {
        if (token.isNullOrEmpty()) return TokenInfo(null, expired = true, fingerprint = "")
        val payload = decodeJwt(token) ?: return TokenInfo(null, expired = true, fingerprint = "")
        val expSec = (payload["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
        val expires = expSec?.let { Instant.ofEpochSecond(it) }
        val expired = expires == null || expires.isBefore(Instant.now())
        val fingerprint = (payload["fingerprint"] as? JsonPrimitive)?.contentOrNull ?: ""
        return TokenInfo(expires, expired, fingerprint)
    }

    /**
     * POST recreation.gov refresh endpoint. Returns the new access_token on
     * success; null on auth failure. Mirrors auth.js refreshRecgovSession.
     */
    suspend fun refreshAccessToken(
        token: String,
        creds: RefreshCreds,
        client: HttpClient = sharedClient,
    ): String? = refreshRecaccount(token, creds, client)?.get("access_token")?.jsonPrimitiveContent()

    /**
     * Variant of [refreshAccessToken] that returns the full recaccount-shaped
     * response JSON (`{access_token, expiration, account: {...}, ...}`) instead
     * of just the bearer string. Used by the companion-facing
     * `/api/campsite/recgov/fresh-token` endpoint and TokenManager's cache.
     */
    suspend fun refreshRecaccount(
        token: String,
        creds: RefreshCreds,
        client: HttpClient = sharedClient,
    ): JsonObject? {
        val fingerprint = tokenInfo(token).fingerprint
        return runCatching {
            val resp =
                client.post("https://www.recreation.gov/api/accounts/login/v2/refresh") {
                    header("Authorization", "Bearer $token")
                    if (fingerprint.isNotEmpty()) header("Cookie", "r1s-fingerprint=$fingerprint")
                    contentType(ContentType.parse("text/plain;charset=UTF-8"))
                    setBody(creds.toJson())
                }
            if (!resp.status.isSuccess()) {
                log.info("RecgovAuth: refresh HTTP ${resp.status} — ${resp.bodyAsText().take(200)}")
                return@runCatching null
            }
            Json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
        }.onFailure { log.info("RecgovAuth: refresh threw — ${it.message}") }.getOrNull()
    }

    /**
     * Build a recaccount-shaped object directly from a JWT, when no refresh has
     * happened yet (or refresh creds are missing). Mirrors
     * companion/auth.js buildRecaccountFromToken — used as a fallback so the
     * `/recgov/fresh-token` endpoint always returns a usable shape if there's
     * any non-expired token at all.
     */
    fun buildRecaccountFromToken(token: String): JsonObject? {
        val payload = decodeJwt(token) ?: return null
        val expSec = (payload["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
        val expIso =
            expSec
                ?.let { Instant.ofEpochSecond(it).toString() }
                ?: Instant.now().plusSeconds(30 * 60).toString()
        val acct = payload["acct"] as? JsonObject
        val accountId =
            acct?.get("account_id")?.jsonPrimitiveContent()
                ?: payload["sub"]?.jsonPrimitiveContent().orEmpty()
        return buildJsonObject {
            put("access_token", token)
            put("expiration", expIso)
            put(
                "account",
                buildJsonObject {
                    put("account_id", accountId)
                    put("email", acct?.get("email")?.jsonPrimitiveContent().orEmpty())
                    put("first_name", acct?.get("first_name")?.jsonPrimitiveContent().orEmpty())
                    put("last_name", acct?.get("last_name")?.jsonPrimitiveContent().orEmpty())
                },
            )
            put("is_guest", false)
            put("refresh_id", "")
        }
    }

    private fun padBase64Url(s: String): String {
        val pad = (4 - s.length % 4) % 4
        return s + "=".repeat(pad)
    }

    private val sharedClient: HttpClient by lazy {
        HttpClient(CIO) { engine { requestTimeout = 10_000 } }
    }
}

/**
 * PATCH rec.gov's shopping-cart expiration endpoint to keep the hold alive.
 * Empty JSON body — rec.gov resets the timer from the request alone.
 *
 * Sends both the Bearer token and the matching r1s-fingerprint cookie. rec.gov
 * pins the JWT's `fingerprint` claim against the cookie; without the cookie,
 * the cart endpoints return 401 {"error":"bad fingerprint"}. Not full Akamai
 * TLS fingerprinting (like supercharger pricing), so a plain HTTP client works.
 *
 * Returns true on a 2xx response, false otherwise.
 */
suspend fun extendCartHold(
    token: String,
    client: HttpClient = HttpClient(CIO) { engine { requestTimeout = 10_000 } },
): Boolean =
    runCatching {
        val fingerprint = RecgovAuth.tokenInfo(token).fingerprint
        val resp =
            client.patch("https://www.recreation.gov/api/cart/shoppingcart/expiration") {
                header("Authorization", "Bearer $token")
                if (fingerprint.isNotEmpty()) header("Cookie", "r1s-fingerprint=$fingerprint")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        if (!resp.status.isSuccess()) {
            log.info("extendCartHold: HTTP ${resp.status} — ${resp.bodyAsText().take(200)}")
            false
        } else {
            true
        }
    }.onFailure { log.info("extendCartHold: threw — ${it.message}") }.getOrDefault(false)

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull
