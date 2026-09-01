package ca.floo.roadtrip.route.api.settings

import ca.floo.roadtrip.model.api.BookingSettingsDto
import ca.floo.roadtrip.model.api.EmailTestResponseDto
import ca.floo.roadtrip.model.api.NotificationsDto
import ca.floo.roadtrip.model.api.ProfileDto
import ca.floo.roadtrip.model.api.SettingsResponseDto
import ca.floo.roadtrip.model.api.SlackTestResponseDto
import ca.floo.roadtrip.model.api.UpdateNotificationsRequest
import ca.floo.roadtrip.model.api.UpdateProfileRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.route.auth.SESSION_COOKIE
import ca.floo.roadtrip.route.auth.roadtripAuthorization
import ca.floo.roadtrip.service.settings.SettingsError
import ca.floo.roadtrip.service.settings.UserSettingsPort
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val SETTINGS_PATH = "/api/settings"
private const val PROFILE_PATH = "$SETTINGS_PATH/profile"
private const val NOTIFICATIONS_PATH = "$SETTINGS_PATH/notifications"
private const val SLACK_DISCONNECT_PATH = "$NOTIFICATIONS_PATH/slack"
private const val SLACK_TEST_PATH = "$NOTIFICATIONS_PATH/slack/test"
private const val EMAIL_TEST_PATH = "$NOTIFICATIONS_PATH/email/test"

private const val USER_TOKEN = "user-token"
private val testUserId = UserId(42L)

/** Minimal stub of [UserSettingsPort] with injectable lambda behaviors. */
private class StubSettingsService(
    private val onRead: (Principal.User) -> SettingsResponseDto = { defaultSettingsDto() },
    private val onUpdateProfile: (UserId, UpdateProfileRequest) -> SettingsResponseDto = { _, _ -> defaultSettingsDto() },
    private val onUpdateNotifications: suspend (Principal.User, UpdateNotificationsRequest) -> SettingsResponseDto =
        { _, _ -> defaultSettingsDto() },
    private val onDisconnectSlack: (UserId) -> SettingsResponseDto = { defaultSettingsDto(slackConfigured = false) },
    private val onSendSlackTest: suspend (UserId, String?) -> SlackTestResponseDto =
        { _, ch -> SlackTestResponseDto(sent = true, channel = ch) },
    private val onSendEmailTest: suspend (UserId) -> EmailTestResponseDto =
        { _ -> EmailTestResponseDto(sent = true, recipient = "user@example.com") },
) : UserSettingsPort {
    override fun read(principal: Principal.User): SettingsResponseDto = onRead(principal)

    override fun updateProfile(
        userId: UserId,
        req: UpdateProfileRequest,
    ): SettingsResponseDto = onUpdateProfile(userId, req)

    override suspend fun updateNotifications(
        principal: Principal.User,
        req: UpdateNotificationsRequest,
    ): SettingsResponseDto = onUpdateNotifications(principal, req)

    override fun disconnectSlack(userId: UserId): SettingsResponseDto = onDisconnectSlack(userId)

    override suspend fun sendSlackTest(
        userId: UserId,
        channelOverride: String?,
    ): SlackTestResponseDto = onSendSlackTest(userId, channelOverride)

    override suspend fun sendEmailTest(userId: UserId): EmailTestResponseDto = onSendEmailTest(userId)
}

private fun defaultSettingsDto(slackConfigured: Boolean = false): SettingsResponseDto =
    SettingsResponseDto(
        profile =
            ProfileDto(
                displayName = "Alice",
                loginEmail = "alice@example.com",
                isEmailVerified = true,
                roles = emptyList(),
                providerLabel = null,
                theme = "system",
            ),
        notifications =
            NotificationsDto(
                notificationEmail = "alice@example.com",
                slackChannel = null,
                slackConfigured = slackConfigured,
                slackTokenHint = null,
            ),
        booking = BookingSettingsDto(recgovConfigured = false, recgovUsername = null, recgovPasswordHint = null),
    )

private fun resolve(token: String?): Principal =
    when (token) {
        USER_TOKEN -> Principal.User(testUserId, roles = emptySet())
        else -> Principal.Anonymous
    }

class SettingsRoutesTest {
    // ── Anonymous → 401 on every endpoint ─────────────────────────────────────

    @Test
    fun `GET settings anonymous returns 401`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp = client.get(SETTINGS_PATH)
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `PUT profile anonymous returns 401`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp =
                client.put(PROFILE_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `PUT notifications anonymous returns 401`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp =
                client.put(NOTIFICATIONS_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `DELETE slack anonymous returns 401`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp = client.delete(SLACK_DISCONNECT_PATH)
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `POST slack test anonymous returns 401`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp =
                client.post(SLACK_TEST_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    // ── Authenticated → 200, token never in GET output ─────────────────────────

    @Test
    fun `GET settings authenticated returns 200 and no token in body`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp = client.get(SETTINGS_PATH) { userSession() }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val profile = body["profile"]!!.jsonObject
            assertEquals("alice@example.com", profile["login_email"]!!.jsonPrimitive.content)
            val notifications = body["notifications"]!!.jsonObject
            assertFalse(notifications.containsKey("slack_token"), "slack_token must never appear in response")
            assertFalse(body.containsKey("slack_token"), "slack_token must never appear at top level")
        }

    @Test
    fun `PUT profile authenticated returns 200 with updated settings`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing {
                    settingsRoutes(
                        StubSettingsService(
                            onUpdateProfile = { _, req ->
                                defaultSettingsDto().copy(
                                    profile =
                                        defaultSettingsDto().profile.copy(
                                            displayName = req.displayName ?: "Alice",
                                        ),
                                )
                            },
                        ),
                    )
                }
            }
            val resp =
                client.put(PROFILE_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("""{"display_name": "Bob"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("Bob", body["profile"]!!.jsonObject["display_name"]!!.jsonPrimitive.content)
        }

    // ── PUT notifications error mapping ────────────────────────────────────────

    @Test
    fun `PUT notifications with slack-rejected token returns 400 slack_invalid_auth`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing {
                    settingsRoutes(
                        StubSettingsService(
                            onUpdateNotifications = { _, _ -> throw SettingsError.SlackRejected() },
                        ),
                    )
                }
            }
            val resp =
                client.put(NOTIFICATIONS_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("""{"slack_token": "xoxb-bad-token"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("slack_invalid_auth", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PUT notifications with invalid field returns 400 invalid_field`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing {
                    settingsRoutes(
                        StubSettingsService(
                            onUpdateNotifications = { _, _ -> throw SettingsError.InvalidField("notification_email invalid") },
                        ),
                    )
                }
            }
            val resp =
                client.put(NOTIFICATIONS_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("""{"notification_email": "not-an-email"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_field", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PUT notifications when encryption unavailable returns 503`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing {
                    settingsRoutes(
                        StubSettingsService(
                            onUpdateNotifications = { _, _ -> throw SettingsError.EncryptionUnavailable() },
                        ),
                    )
                }
            }
            val resp =
                client.put(NOTIFICATIONS_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("""{"slack_token": "xoxb-fine-token"}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("encryption_unavailable", body["error"]!!.jsonPrimitive.content)
        }

    // ── POST slack test error mapping ──────────────────────────────────────────

    @Test
    fun `POST slack test with no stored token returns 503 slack_not_configured`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing {
                    settingsRoutes(
                        StubSettingsService(
                            onSendSlackTest = { _, _ -> throw SettingsError.SlackNotConfigured() },
                        ),
                    )
                }
            }
            val resp =
                client.post(SLACK_TEST_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("slack_not_configured", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST slack test with send failure returns 502 slack_send_failed`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing {
                    settingsRoutes(
                        StubSettingsService(
                            onSendSlackTest = { _, _ -> throw SettingsError.SlackSendFailed() },
                        ),
                    )
                }
            }
            val resp =
                client.post(SLACK_TEST_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadGateway, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("slack_send_failed", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST slack test with channel override returns 200 with channel`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp =
                client.post(SLACK_TEST_PATH) {
                    userSession()
                    contentType(ContentType.Application.Json)
                    setBody("""{"channel": "#camping"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, body["sent"]!!.jsonPrimitive.boolean)
            assertEquals("#camping", body["channel"]!!.jsonPrimitive.content)
        }

    // ── POST email test ────────────────────────────────────────────────────────

    @Test
    fun `POST email test anonymous returns 401`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp = client.post(EMAIL_TEST_PATH)
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `POST email test authenticated returns 200 with sent and recipient`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp = client.post(EMAIL_TEST_PATH) { userSession() }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, body["sent"]!!.jsonPrimitive.boolean)
            assertEquals("user@example.com", body["recipient"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST email test with send failure returns 502 email_send_failed`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing {
                    settingsRoutes(
                        StubSettingsService(
                            onSendEmailTest = { _ -> throw SettingsError.EmailSendFailed() },
                        ),
                    )
                }
            }
            val resp = client.post(EMAIL_TEST_PATH) { userSession() }
            assertEquals(HttpStatusCode.BadGateway, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("email_send_failed", body["error"]!!.jsonPrimitive.content)
        }

    // ── DELETE slack → 200 with slack_configured: false ─────────────────────────

    @Test
    fun `DELETE slack returns 200 with slack_configured false`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolve }
                routing { settingsRoutes(StubSettingsService()) }
            }
            val resp = client.delete(SLACK_DISCONNECT_PATH) { userSession() }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val notifications = body["notifications"]!!.jsonObject
            assertEquals(false, notifications["slack_configured"]!!.jsonPrimitive.boolean)
        }
}

private fun io.ktor.client.request.HttpRequestBuilder.userSession() {
    header(HttpHeaders.Cookie, "$SESSION_COOKIE=$USER_TOKEN")
}
