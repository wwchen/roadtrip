package ca.floo.roadtrip.service.booking.adapters.recgov

import ca.floo.roadtrip.clients.recgov.RecGovAuthClient
import ca.floo.roadtrip.clients.recgov.RecGovRefreshCredentials
import ca.floo.roadtrip.models.api.RecGovAccountSchema
import ca.floo.roadtrip.models.api.RecGovRecaccountSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TEST_ACCOUNT_ID = "acct-1"
private const val TEST_EMAIL = "test@example.com"
private const val TEST_REFRESH_ID = "refresh-1"
private const val TEST_REFRESHED_TOKEN = "refreshed-token"
private const val TEST_FINGERPRINT = "fp-1"
private const val JWT_HEADER = """{"alg":"none"}"""
private const val JWT_SIGNATURE = "sig"
private const val FRESH_TOKEN_OFFSET_SECONDS = 60L * 60L
private const val NEAR_EXPIRY_TOKEN_OFFSET_SECONDS = 60L
private const val EXPIRED_TOKEN_OFFSET_SECONDS = -60L
private const val TEST_REFRESH_AHEAD_MINUTES = 5L

private val testNow: Instant = Instant.parse("2026-07-15T20:00:00Z")
private val testClock: Clock = Clock.fixed(testNow, ZoneOffset.UTC)
private val testJson = Json { encodeDefaults = true }

class RecGovBookingSessionServiceTest {
    @Test
    fun `fresh recaccount returns configured token without refresh`() =
        runBlocking {
            val client = RecordingAuthClient()
            val service =
                service(
                    recaccount = recaccount(fakeJwt(testNow.plusSeconds(FRESH_TOKEN_OFFSET_SECONDS))),
                    client = client,
                )

            val result = service.freshRecaccount()

            assertEquals(TEST_ACCOUNT_ID, result?.account?.accountId)
            assertEquals(0, client.calls.size)
        }

    @Test
    fun `expired recaccount without refresh credentials returns null`() =
        runBlocking {
            val service =
                service(
                    recaccount =
                        recaccount(
                            token = fakeJwt(testNow.plusSeconds(EXPIRED_TOKEN_OFFSET_SECONDS)),
                            refreshId = "",
                        ),
                    client = RecordingAuthClient(),
                )

            assertNull(service.freshRecaccount())
        }

    @Test
    fun `near expiry recaccount refreshes through recgov auth client`() =
        runBlocking {
            val refreshed = recaccount(TEST_REFRESHED_TOKEN)
            val client = RecordingAuthClient(refreshed)
            val service =
                service(
                    recaccount = recaccount(fakeJwt(testNow.plusSeconds(NEAR_EXPIRY_TOKEN_OFFSET_SECONDS))),
                    client = client,
                )

            val result = service.freshRecaccount()

            assertEquals(TEST_REFRESHED_TOKEN, result?.accessToken)
            assertEquals(1, client.calls.size)
            val refreshCredentials = client.calls.single().credentials
            assertEquals(TEST_ACCOUNT_ID, refreshCredentials.accountId)
            assertEquals(TEST_REFRESH_ID, refreshCredentials.refreshId)
        }

    @Test
    fun `import recaccount seeds future fresh recaccount calls`() =
        runBlocking {
            val client = RecordingAuthClient()
            val service = emptyService(client)
            val imported = recaccount(fakeJwt(testNow.plusSeconds(FRESH_TOKEN_OFFSET_SECONDS)))

            val result = service.importRecaccount(testJson.encodeToString(imported))

            assertEquals(imported.accessToken, result?.accessToken)
            assertEquals(imported.accessToken, service.freshRecaccount()?.accessToken)
            assertEquals(0, client.calls.size)
        }
}

private fun service(
    recaccount: RecGovRecaccountSchema,
    client: RecordingAuthClient,
): RecGovBookingSessionService =
    RecGovBookingSessionService(
        seedJson = testJson.encodeToString(recaccount),
        authClient = client,
        clock = testClock,
        refreshAheadOfExpiry = Duration.ofMinutes(TEST_REFRESH_AHEAD_MINUTES),
    )

private fun emptyService(client: RecordingAuthClient): RecGovBookingSessionService =
    RecGovBookingSessionService(
        seedJson = null,
        authClient = client,
        clock = testClock,
        refreshAheadOfExpiry = Duration.ofMinutes(TEST_REFRESH_AHEAD_MINUTES),
    )

private class RecordingAuthClient(
    private val response: RecGovRecaccountSchema? = null,
) : RecGovAuthClient {
    val calls = mutableListOf<RefreshCall>()

    override suspend fun refreshRecaccount(
        token: String,
        credentials: RecGovRefreshCredentials,
    ): RecGovRecaccountSchema? {
        calls += RefreshCall(token, credentials)
        return response
    }
}

private data class RefreshCall(
    val token: String,
    val credentials: RecGovRefreshCredentials,
)

private fun recaccount(
    token: String,
    refreshId: String = TEST_REFRESH_ID,
): RecGovRecaccountSchema =
    RecGovRecaccountSchema(
        accessToken = token,
        expiration = testNow.plusSeconds(FRESH_TOKEN_OFFSET_SECONDS).toString(),
        account = RecGovAccountSchema(accountId = TEST_ACCOUNT_ID, email = TEST_EMAIL),
        isGuest = false,
        refreshId = refreshId,
    )

private fun fakeJwt(expires: Instant): String {
    val payload =
        """{"exp":${expires.epochSecond},"fingerprint":"$TEST_FINGERPRINT","acct":{"account_id":"$TEST_ACCOUNT_ID","email":"$TEST_EMAIL"}}"""
    return "${base64Url(JWT_HEADER)}.${base64Url(payload)}.$JWT_SIGNATURE"
}

private fun base64Url(raw: String): String =
    Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.toByteArray(Charsets.UTF_8))
