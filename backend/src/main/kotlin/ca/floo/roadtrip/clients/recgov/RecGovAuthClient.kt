package ca.floo.roadtrip.clients.recgov

import ca.floo.roadtrip.models.api.RecGovAccountSchema
import ca.floo.roadtrip.models.api.RecGovRecaccountSchema
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64

private const val RECGOV_REFRESH_URL = "https://www.recreation.gov/api/accounts/login/v2/refresh"
private const val RECGOV_AUTH_REQUEST_TIMEOUT_MILLIS = 10_000L
private const val RECGOV_REFRESH_CONTENT_TYPE = "text/plain;charset=UTF-8"
private const val AUTHORIZATION_HEADER_PREFIX = "Bearer "
private const val FINGERPRINT_COOKIE_PREFIX = "r1s-fingerprint="
private const val JWT_PAYLOAD_PART_INDEX = 1
private const val JWT_MIN_PARTS = 2
private const val BASE64_GROUP_SIZE = 4
private const val DEFAULT_TOKEN_TTL_SECONDS = 30L * 60L
private const val LOG_BODY_LIMIT = 200

private val log = LoggerFactory.getLogger(HttpRecGovAuthClient::class.java)

private val recgovAuthJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

data class RecGovRefreshCredentials(
    val accountId: String,
    val refreshId: String,
)

data class RecGovTokenInfo(
    val expires: Instant?,
    val expired: Boolean,
    val fingerprint: String,
)

interface RecGovAuthClient : AutoCloseable {
    suspend fun refreshRecaccount(
        token: String,
        credentials: RecGovRefreshCredentials,
    ): RecGovRecaccountSchema?

    override fun close() = Unit
}

class HttpRecGovAuthClient(
    private val client: HttpClient =
        HttpClient(CIO) {
            engine { requestTimeout = RECGOV_AUTH_REQUEST_TIMEOUT_MILLIS }
        },
) : RecGovAuthClient {
    override suspend fun refreshRecaccount(
        token: String,
        credentials: RecGovRefreshCredentials,
    ): RecGovRecaccountSchema? {
        val fingerprint = RecGovJwt.tokenInfo(token).fingerprint
        return runCatching {
            val resp =
                client.post(RECGOV_REFRESH_URL) {
                    header("Authorization", "$AUTHORIZATION_HEADER_PREFIX$token")
                    if (fingerprint.isNotEmpty()) header("Cookie", "$FINGERPRINT_COOKIE_PREFIX$fingerprint")
                    contentType(ContentType.parse(RECGOV_REFRESH_CONTENT_TYPE))
                    setBody(credentials.toJson())
                }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                log.info("RecGov auth refresh HTTP {} {}", resp.status.value, text.take(LOG_BODY_LIMIT))
                return@runCatching null
            }
            recgovAuthJson.decodeFromString<RecGovRecaccountSchema>(text)
        }.onFailure {
            log.info("RecGov auth refresh failed: {}", it.message)
        }.getOrNull()
    }

    override fun close() {
        client.close()
    }
}

object RecGovJwt {
    fun tokenInfo(token: String?): RecGovTokenInfo {
        if (token.isNullOrBlank()) return RecGovTokenInfo(expires = null, expired = true, fingerprint = "")
        val payload = decodeJwt(token) ?: return RecGovTokenInfo(expires = null, expired = true, fingerprint = "")
        val expSec = (payload["exp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val expires = expSec?.let(Instant::ofEpochSecond)
        val expired = expires == null || expires.isBefore(Instant.now())
        val fingerprint = payload["fingerprint"]?.jsonPrimitiveContent().orEmpty()
        return RecGovTokenInfo(expires = expires, expired = expired, fingerprint = fingerprint)
    }

    fun buildRecaccountFromToken(token: String): RecGovRecaccountSchema? {
        val payload = decodeJwt(token) ?: return null
        val expSec = (payload["exp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val expiration =
            expSec
                ?.let { Instant.ofEpochSecond(it).toString() }
                ?: Instant.now().plusSeconds(DEFAULT_TOKEN_TTL_SECONDS).toString()
        val account = payload["acct"] as? JsonObject
        val accountId =
            account?.get("account_id")?.jsonPrimitiveContent()
                ?: payload["sub"]?.jsonPrimitiveContent().orEmpty()
        return RecGovRecaccountSchema(
            accessToken = token,
            expiration = expiration,
            account =
                RecGovAccountSchema(
                    accountId = accountId,
                    email = account?.get("email")?.jsonPrimitiveContent().orEmpty(),
                    firstName = account?.get("first_name")?.jsonPrimitiveContent().orEmpty(),
                    lastName = account?.get("last_name")?.jsonPrimitiveContent().orEmpty(),
                ),
            isGuest = false,
            refreshId = "",
        )
    }

    private fun decodeJwt(token: String): JsonObject? =
        runCatching {
            val parts = token.split('.')
            if (parts.size < JWT_MIN_PARTS) return@runCatching null
            val payload = String(Base64.getUrlDecoder().decode(padBase64Url(parts[JWT_PAYLOAD_PART_INDEX])))
            recgovAuthJson.parseToJsonElement(payload).jsonObject
        }.getOrNull()

    private fun padBase64Url(value: String): String {
        val padding = (BASE64_GROUP_SIZE - value.length % BASE64_GROUP_SIZE) % BASE64_GROUP_SIZE
        return value + "=".repeat(padding)
    }
}

internal fun parseRecGovRecaccount(raw: String): RecGovRecaccountSchema? =
    runCatching {
        recgovAuthJson.decodeFromString<RecGovRecaccountSchema>(raw)
    }.getOrNull()

internal fun RecGovRecaccountSchema.refreshCredentials(): RecGovRefreshCredentials? {
    val accountId = account.accountId.takeIf { it.isNotBlank() } ?: return null
    val refreshId = refreshId.takeIf { it.isNotBlank() } ?: return null
    return RecGovRefreshCredentials(accountId = accountId, refreshId = refreshId)
}

private fun RecGovRefreshCredentials.toJson(): String =
    recgovAuthJson.encodeToString(
        RecGovRefreshCredentialsDto(
            accountId = accountId,
            refreshId = refreshId,
        ),
    )

@Serializable
private data class RecGovRefreshCredentialsDto(
    @SerialName("account_id")
    val accountId: String,
    @SerialName("refresh_id")
    val refreshId: String,
)

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull
