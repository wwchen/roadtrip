package ca.floo.roadtrip.route.api.settings

import ca.floo.roadtrip.model.api.BookingSettingsDto
import ca.floo.roadtrip.model.api.RecgovLoginResponseDto
import ca.floo.roadtrip.model.api.RecgovLoginStatus
import ca.floo.roadtrip.model.api.RecgovRemovedDto
import ca.floo.roadtrip.model.api.RecgovSessionState
import ca.floo.roadtrip.model.api.RecgovStatusDto
import ca.floo.roadtrip.model.api.RecgovVerifyResponseDto
import ca.floo.roadtrip.model.api.UpdateRecgovRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.route.auth.SESSION_COOKIE
import ca.floo.roadtrip.route.auth.roadtripAuthorization
import ca.floo.roadtrip.service.settings.RecGovCredentialPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import ca.floo.roadtrip.service.settings.SettingsError
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RECGOV_PATH = "/api/settings/recgov"
private const val LOGIN_PATH = "$RECGOV_PATH/login"
private const val MFA_PATH = "$RECGOV_PATH/login/mfa"
private const val VERIFY_PATH = "$RECGOV_PATH/verify"
private const val STATUS_PATH = "$RECGOV_PATH/status"

private const val USER_TOKEN = "user-token"
private val testUserId = UserId(42L)

private val configuredBooking =
    BookingSettingsDto(recgovConfigured = true, recgovUsername = "ada@example.com", recgovPasswordHint = "cret")

/** Stub of [RecGovCredentialPort] with injectable behaviors. */
private class StubRecgovService(
    private val onSave: (UserId, UpdateRecgovRequest) -> BookingSettingsDto = { _, _ -> configuredBooking },
    private val onRemove: suspend (UserId) -> RecgovRemovedDto =
        { RecgovRemovedDto(removed = true, strandedAtcWatches = 0, companionSignedOut = true) },
    private val onLogin: suspend (UserId) -> RecgovLoginResponseDto = { RecgovLoginResponseDto(RecgovLoginStatus.OK) },
    private val onMfa: suspend (UserId, String) -> RecgovLoginResponseDto = { _, _ -> RecgovLoginResponseDto(RecgovLoginStatus.OK) },
    private val onVerify: suspend (UserId) -> RecgovVerifyResponseDto = { RecgovVerifyResponseDto(ok = true) },
    private val onStatus: suspend (UserId) -> RecgovStatusDto =
        {
            RecgovStatusDto(
                configured = true,
                username = "ada@example.com",
                passwordHint = "cret",
                session = RecgovSessionState.ACTIVE,
            )
        },
) : RecGovCredentialPort {
    val savedRequests = mutableListOf<UpdateRecgovRequest>()
    val mfaCodes = mutableListOf<String>()

    override fun save(
        userId: UserId,
        req: UpdateRecgovRequest,
    ): BookingSettingsDto {
        savedRequests += req
        return onSave(userId, req)
    }

    override suspend fun remove(userId: UserId): RecgovRemovedDto = onRemove(userId)

    override suspend fun login(userId: UserId): RecgovLoginResponseDto = onLogin(userId)

    override suspend fun completeMfa(
        userId: UserId,
        code: String,
    ): RecgovLoginResponseDto {
        mfaCodes += code
        return onMfa(userId, code)
    }

    override suspend fun verify(userId: UserId): RecgovVerifyResponseDto = onVerify(userId)

    override suspend fun status(userId: UserId): RecgovStatusDto = onStatus(userId)
}

private fun resolve(token: String?): Principal =
    when (token) {
        USER_TOKEN -> Principal.User(testUserId, roles = emptySet())
        else -> Principal.Anonymous
    }

private fun ApplicationTestBuilder.mount(service: RecGovCredentialPort) {
    application {
        install(roadtripAuthorization) { resolvePrincipal = ::resolve }
        routing { recgovSettingsRoutes(service) }
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.asUser() = header(HttpHeaders.Cookie, "$SESSION_COOKIE=$USER_TOKEN")

class RecgovSettingsRoutesTest {
    // ── Access ───────────────────────────────────────────────────────────────

    @Test
    fun `every recgov endpoint refuses an anonymous caller`() =
        testApplication {
            mount(StubRecgovService())

            assertEquals(HttpStatusCode.Unauthorized, client.get(STATUS_PATH).status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete(RECGOV_PATH).status)
            assertEquals(HttpStatusCode.Unauthorized, client.post(LOGIN_PATH).status)
            assertEquals(HttpStatusCode.Unauthorized, client.post(VERIFY_PATH).status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client
                    .put(RECGOV_PATH) {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }.status,
            )
        }

    // ── PUT ──────────────────────────────────────────────────────────────────

    @Test
    fun `saving credentials answers with the summary and never the password`() =
        testApplication {
            val service = StubRecgovService()
            mount(service)

            val resp =
                client.put(RECGOV_PATH) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"ada@example.com","password":"hunter2-secret"}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertFalse(body.contains("hunter2-secret"), body)
            assertFalse(body.contains("password\":"), body)
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("ada@example.com", json["recgov_username"]!!.jsonPrimitive.content)
            assertEquals("cret", json["recgov_password_hint"]!!.jsonPrimitive.content)
            assertEquals("hunter2-secret", service.savedRequests.single().password)
        }

    @Test
    fun `an omitted password reaches the service as null, meaning unchanged`() =
        testApplication {
            val service = StubRecgovService()
            mount(service)

            client.put(RECGOV_PATH) {
                asUser()
                contentType(ContentType.Application.Json)
                setBody("""{"username":"ada@example.com"}""")
            }

            assertNull(service.savedRequests.single().password)
        }

    @Test
    fun `a malformed body never echoes back what was sent`() =
        testApplication {
            mount(StubRecgovService())

            // kotlinx's parser message quotes the offending input, which for this
            // route is a password. The 400 carries a fixed detail instead.
            val resp =
                client.put(RECGOV_PATH) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"ada@example.com","password":"hunter2-secret",}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = resp.bodyAsText()
            assertEquals(ERROR_INVALID_BODY, errorCode(body))
            assertFalse(body.contains("hunter2-secret"), body)
        }

    @Test
    fun `a rejected field is a 400 invalid_field`() =
        testApplication {
            mount(StubRecgovService(onSave = { _, _ -> throw SettingsError.InvalidField("username must not be blank") }))

            val resp =
                client.put(RECGOV_PATH) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"  "}""")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(ERROR_INVALID_FIELD, errorCode(resp.bodyAsText()))
        }

    @Test
    fun `no encryption key is a 503 encryption_unavailable`() =
        testApplication {
            mount(StubRecgovService(onSave = { _, _ -> throw SettingsError.EncryptionUnavailable() }))

            val resp =
                client.put(RECGOV_PATH) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"ada@example.com","password":"x"}""")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals(ERROR_ENCRYPTION_UNAVAILABLE, errorCode(resp.bodyAsText()))
        }

    // ── DELETE ───────────────────────────────────────────────────────────────

    @Test
    fun `removal reports the stranded active atc watches`() =
        testApplication {
            mount(
                StubRecgovService(
                    onRemove = { RecgovRemovedDto(removed = true, strandedAtcWatches = 2, companionSignedOut = false) },
                ),
            )

            val resp = client.delete(RECGOV_PATH) { asUser() }

            assertEquals(HttpStatusCode.OK, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(2, json["stranded_atc_watches"]!!.jsonPrimitive.int)
            assertFalse(json["companion_signed_out"]!!.jsonPrimitive.content.toBoolean())
        }

    // ── Login and MFA ────────────────────────────────────────────────────────

    @Test
    fun `an MFA prompt is a 200 carrying the challenge id`() =
        testApplication {
            mount(
                StubRecgovService(
                    onLogin = {
                        RecgovLoginResponseDto(
                            status = RecgovLoginStatus.MFA_REQUIRED,
                            challengeId = "chal-1",
                            expiresAt = "2026-08-31T00:05:00Z",
                        )
                    },
                ),
            )

            val resp = client.post(LOGIN_PATH) { asUser() }

            assertEquals(HttpStatusCode.OK, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(RecgovLoginStatus.MFA_REQUIRED, json["status"]!!.jsonPrimitive.content)
            assertEquals("chal-1", json["challenge_id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a captcha is a 200 failed status, not an HTTP error`() =
        testApplication {
            mount(
                StubRecgovService(
                    onLogin = {
                        RecgovLoginResponseDto(status = RecgovLoginStatus.FAILED, error = RecGovSessionCodes.CAPTCHA_REQUIRED)
                    },
                ),
            )

            val resp = client.post(LOGIN_PATH) { asUser() }

            assertEquals(HttpStatusCode.OK, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(RecgovLoginStatus.FAILED, json["status"]!!.jsonPrimitive.content)
            assertEquals(RecGovSessionCodes.CAPTCHA_REQUIRED, json["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `login without stored credentials is a 409 recgov_not_configured`() =
        testApplication {
            mount(StubRecgovService(onLogin = { throw SettingsError.RecgovNotConfigured() }))

            val resp = client.post(LOGIN_PATH) { asUser() }

            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals(ERROR_RECGOV_NOT_CONFIGURED, errorCode(resp.bodyAsText()))
        }

    @Test
    fun `the MFA route forwards the code`() =
        testApplication {
            val service = StubRecgovService()
            mount(service)

            val resp =
                client.post(MFA_PATH) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("""{"code":"123456"}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(listOf("123456"), service.mfaCodes)
        }

    @Test
    fun `an MFA body with no code is a 400 invalid_body`() =
        testApplication {
            mount(StubRecgovService())

            val resp =
                client.post(MFA_PATH) {
                    asUser()
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals(ERROR_INVALID_BODY, errorCode(resp.bodyAsText()))
        }

    // ── Verify and status ────────────────────────────────────────────────────

    @Test
    fun `verify reports the dry-run outcome`() =
        testApplication {
            mount(
                StubRecgovService(
                    onVerify = { RecgovVerifyResponseDto(ok = false, error = RecGovSessionCodes.NOT_AUTHENTICATED) },
                ),
            )

            val resp = client.post(VERIFY_PATH) { asUser() }

            assertEquals(HttpStatusCode.OK, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(RecGovSessionCodes.NOT_AUTHENTICATED, json["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `status reports an open MFA challenge`() =
        testApplication {
            mount(
                StubRecgovService(
                    onStatus = {
                        RecgovStatusDto(
                            configured = true,
                            username = "ada@example.com",
                            passwordHint = "cret",
                            session = RecgovSessionState.EXPIRED,
                            mfaPending = true,
                        )
                    },
                ),
            )

            val resp = client.get(STATUS_PATH) { asUser() }

            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertTrue(json["mfa_pending"]!!.jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `status degrades to companion_unavailable rather than erroring`() =
        testApplication {
            mount(
                StubRecgovService(
                    onStatus = {
                        RecgovStatusDto(
                            configured = true,
                            username = "ada@example.com",
                            passwordHint = "cret",
                            session = RecgovSessionState.COMPANION_UNAVAILABLE,
                            detail = "connection refused",
                        )
                    },
                ),
            )

            val resp = client.get(STATUS_PATH) { asUser() }

            assertEquals(HttpStatusCode.OK, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(RecgovSessionState.COMPANION_UNAVAILABLE, json["session"]!!.jsonPrimitive.content)
            assertTrue(json["configured"]!!.jsonPrimitive.content.toBoolean())
        }

    private fun errorCode(body: String): String? =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonPrimitive
            ?.content
}
