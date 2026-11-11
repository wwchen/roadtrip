package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.BookingRelayResponseDto
import ca.floo.roadtrip.route.internal.bookingRelayRoutes
import ca.floo.roadtrip.service.booking.BookingRelayController
import ca.floo.roadtrip.service.booking.RECGOV_CART_URL
import ca.floo.roadtrip.service.booking.RecGovAtcExecutor
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "relay-token"
private const val PROFILE_ID = "n:7"
private const val PREFIX = "/api/internal/booking"
private const val SLOW_CALL_MS = 500L
private val shortTimeout: Duration = Duration.ofMillis(50)
private val wire = Json { ignoreUnknownKeys = true }

private class FakeCompanion : CompanionSessionPort {
    var health: CompanionSessionHealth = CompanionSessionHealth.Active
    var loginResult: CompanionLoginResult = CompanionLoginResult.Ok
    var mfaResult: CompanionLoginResult = CompanionLoginResult.Ok
    var logoutResult: CompanionActionResult = CompanionActionResult.Ok
    var destroyResult: CompanionActionResult = CompanionActionResult.Ok
    var refreshResult: CompanionActionResult = CompanionActionResult.Ok
    var verifyResult: CompanionActionResult = CompanionActionResult.Ok
    var slow = false
    val calls = mutableListOf<String>()
    val logins = mutableListOf<Triple<String, String, String>>()
    val mfas = mutableListOf<Triple<String, String, String>>()

    private suspend fun record(call: String) {
        calls += call
        if (slow) delay(SLOW_CALL_MS)
    }

    override suspend fun login(
        profileId: String,
        username: String,
        password: String,
        unattended: Boolean,
    ): CompanionLoginResult {
        record("login:$profileId")
        logins += Triple(profileId, username, password)
        return loginResult
    }

    override suspend fun completeMfa(
        profileId: String,
        challengeId: String,
        code: String,
    ): CompanionLoginResult {
        record("mfa:$profileId")
        mfas += Triple(profileId, challengeId, code)
        return mfaResult
    }

    override suspend fun logout(profileId: String): CompanionActionResult {
        record("logout:$profileId")
        return logoutResult
    }

    override suspend fun destroyProfile(profileId: String): CompanionActionResult {
        record("destroy:$profileId")
        return destroyResult
    }

    override suspend fun verify(profileId: String): CompanionActionResult {
        record("verify:$profileId")
        return verifyResult
    }

    override suspend fun health(profileId: String): CompanionSessionHealth {
        record("health:$profileId")
        return health
    }

    override suspend fun refresh(
        profileId: String,
        unattended: Boolean,
    ): CompanionActionResult {
        record("refresh:$profileId")
        return refreshResult
    }

    override suspend fun markKeepWarm(profileIds: Collection<String>): CompanionActionResult = CompanionActionResult.Ok
}

private class FakeAtc : RecGovAtcExecutor {
    var outcome: RecGovAtcOutcome = RecGovAtcOutcome.Completed(buildJsonObject { put("cart_added", true) })
    val payloads = mutableListOf<JsonObject>()

    override suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome {
        payloads += payload
        return outcome
    }
}

class BookingRelayRoutesTest {
    private val companion = FakeCompanion()
    private val atc = FakeAtc()

    private val profileBody = """{"profile_id":"$PROFILE_ID"}"""
    private val atcBody = """{"profile_id":"$PROFILE_ID","vendor_site_id":"300","start_date":"2026-12-01","end_date":"2026-12-02"}"""
    private val loginBody = """{"profile_id":"$PROFILE_ID","username":"camper@example.com","password":"hunter2"}"""
    private val mfaBody = """{"profile_id":"$PROFILE_ID","challenge_id":"ch-1","mfa_code":"123456"}"""

    private val everyPath =
        listOf(
            "health" to profileBody,
            "refresh" to profileBody,
            "verify" to profileBody,
            "signout" to profileBody,
            "login" to loginBody,
            "login/mfa" to mfaBody,
            "atc" to atcBody,
        )

    private fun relay(
        token: String? = TOKEN,
        timeout: Duration = Duration.ofSeconds(5),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            routeTestApplication {
                bookingRelayRoutes(BookingRelayController(companion, atc, timeout), token)
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(
        path: String,
        body: String,
        bearer: String? = TOKEN,
    ): HttpResponse =
        client.post("$PREFIX/$path") {
            contentType(ContentType.Application.Json)
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private suspend fun HttpResponse.envelope(): BookingRelayResponseDto = wire.decodeFromString(bodyAsText())

    @Test
    fun `routes are absent when the token is unset`() =
        relay(token = null) {
            everyPath.forEach { (path, body) -> assertEquals(HttpStatusCode.NotFound, call(path, body).status, path) }
            assertTrue(companion.calls.isEmpty())
        }

    @Test
    fun `a missing or wrong bearer is 401 and reaches no companion`() =
        relay {
            everyPath.forEach { (path, body) ->
                assertEquals(HttpStatusCode.Unauthorized, call(path, body, bearer = null).status, path)
                assertEquals(HttpStatusCode.Unauthorized, call(path, body, bearer = "nope").status, path)
            }
            assertTrue(companion.calls.isEmpty())
            assertTrue(atc.payloads.isEmpty())
        }

    @Test
    fun `a malformed body is 400`() =
        relay {
            assertEquals(HttpStatusCode.BadRequest, call("atc", """{"profile_id":"$PROFILE_ID"}""").status)
            assertEquals(HttpStatusCode.BadRequest, call("atc", atcBody.replace("2026-12-01", "tomorrow")).status)
        }

    @Test
    fun `atc passes the profile id through verbatim and answers the cart`() =
        relay {
            val response = call("atc", atcBody)

            assertEquals(HttpStatusCode.OK, response.status)
            val envelope = response.envelope()
            assertTrue(envelope.ok)
            assertEquals(RECGOV_CART_URL, envelope.cartUrl)
            assertEquals(listOf("health:$PROFILE_ID"), companion.calls)
            assertEquals(
                PROFILE_ID,
                atc.payloads
                    .single()["profile_id"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "300",
                atc.payloads
                    .single()["campsite_id"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `a refused hold carries the companion code and its category`() =
        relay {
            atc.outcome = RecGovAtcOutcome.Failed(RecGovSessionCodes.PROFILE_BUSY, null)

            val envelope = call("atc", atcBody).envelope()

            assertFalse(envelope.ok)
            assertEquals(RecGovSessionCodes.PROFILE_BUSY, envelope.code)
            assertEquals("retry_later", envelope.category)
        }

    @Test
    fun `a cold session that cannot refresh is not signed in again on the hold`() =
        relay {
            companion.health = CompanionSessionHealth.NeverLoggedIn
            companion.refreshResult = CompanionActionResult.Failed(RecGovSessionCodes.REFRESH_FAILED)

            val envelope = call("atc", atcBody).envelope()

            assertFalse(envelope.ok)
            assertEquals("caller_action", envelope.category)
            assertTrue(companion.logins.isEmpty())
            assertTrue(atc.payloads.isEmpty())
        }

    @Test
    fun `login sends the credentials once and answers mfa_required with its challenge`() =
        relay {
            companion.loginResult = CompanionLoginResult.MfaRequired("ch-9", "2026-12-01T00:05:00Z")

            val envelope = call("login", loginBody).envelope()

            assertFalse(envelope.ok)
            assertEquals(RecGovSessionCodes.MFA_REQUIRED, envelope.code)
            assertEquals("ch-9", envelope.challengeId)
            assertEquals("2026-12-01T00:05:00Z", envelope.expiresAt)
            assertEquals(listOf(Triple(PROFILE_ID, "camper@example.com", "hunter2")), companion.logins)
        }

    @Test
    fun `mfa completes the named challenge`() =
        relay {
            val envelope = call("login/mfa", mfaBody).envelope()

            assertTrue(envelope.ok)
            assertEquals(listOf(Triple(PROFILE_ID, "ch-1", "123456")), companion.mfas)
        }

    @Test
    fun `health names the session state and its code`() =
        relay {
            companion.health = CompanionSessionHealth.Inactive(RecGovSessionCodes.SESSION_LAPSED)

            val envelope = call("health", profileBody).envelope()

            assertFalse(envelope.ok)
            assertEquals("inactive", envelope.health)
            assertEquals(RecGovSessionCodes.SESSION_LAPSED, envelope.code)
        }

    @Test
    fun `refresh and verify answer the companion's result`() =
        relay {
            companion.verifyResult = CompanionActionResult.Failed(RecGovSessionCodes.NOT_AUTHENTICATED, "no session")

            assertTrue(call("refresh", profileBody).envelope().ok)
            val verified = call("verify", profileBody).envelope()
            assertFalse(verified.ok)
            assertEquals(RecGovSessionCodes.NOT_AUTHENTICATED, verified.code)
            assertEquals("no session", verified.detail)
        }

    @Test
    fun `signout logs out then destroys and reports each`() =
        relay {
            companion.logoutResult = CompanionActionResult.Failed("recgov_logout_failed")

            val envelope = call("signout", profileBody).envelope()

            assertTrue(envelope.ok)
            assertEquals(false, envelope.signedOut)
            assertEquals(true, envelope.profileDestroyed)
            assertEquals(listOf("logout:$PROFILE_ID", "destroy:$PROFILE_ID"), companion.calls)
        }

    @Test
    fun `a failed wipe is not ok`() =
        relay {
            companion.destroyResult = CompanionActionResult.Failed("profile_destroy_failed")

            val envelope = call("signout", profileBody).envelope()

            assertFalse(envelope.ok)
            assertEquals(false, envelope.profileDestroyed)
            assertEquals("profile_destroy_failed", envelope.code)
        }

    @Test
    fun `a companion call past the relay timeout answers 504 relay_timeout`() =
        relay(timeout = shortTimeout) {
            companion.slow = true

            val hold = call("atc", atcBody)
            assertEquals(HttpStatusCode.GatewayTimeout, hold.status)
            val envelope = hold.envelope()
            assertEquals("relay_timeout", envelope.code)
            assertEquals("retry_later", envelope.category)
            assertEquals(RECGOV_CART_URL, envelope.cartUrl)

            val health = call("health", profileBody)
            assertEquals(HttpStatusCode.GatewayTimeout, health.status)
            assertNull(health.envelope().cartUrl)
        }
}
