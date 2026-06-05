package ca.floo.campsite.recgov.booker.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
    ): String? {
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
            val body = Json.parseToJsonElement(resp.bodyAsText()) as? JsonObject
            body?.get("access_token")?.jsonPrimitiveContent()
        }.onFailure { log.info("RecgovAuth: refresh threw — ${it.message}") }.getOrNull()
    }

    private fun padBase64Url(s: String): String {
        val pad = (4 - s.length % 4) % 4
        return s + "=".repeat(pad)
    }

    private val sharedClient: HttpClient by lazy {
        HttpClient(CIO) { engine { requestTimeout = 10_000 } }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull
