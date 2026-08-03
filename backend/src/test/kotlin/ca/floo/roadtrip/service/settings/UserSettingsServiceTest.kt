package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.client.resend.EmailDeliveryClient
import ca.floo.roadtrip.client.resend.EmailDeliveryMessage
import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.client.slack.SlackIdentity
import ca.floo.roadtrip.config.EmailConfig
import ca.floo.roadtrip.model.api.UpdateNotificationsRequest
import ca.floo.roadtrip.model.api.UpdateProfileRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.security.SecretCipher
import kotlinx.coroutines.runBlocking
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val detachedCtx = DSL.using(SQLDialect.POSTGRES)
private val testUserId = UserId(1L)
private val testKey = ByteArray(32) { it.toByte() }

/** Fake in-memory implementation of [UserRepo]. Only the fields the service uses. */
private class FakeUserRepo : UserRepo(ctx = detachedCtx) {
    private val users = mutableMapOf<Long, User>()

    fun seed(user: User) {
        users[user.id.value] = user
    }

    override fun findById(id: UserId): User? = users[id.value]

    override fun updateProfile(
        id: UserId,
        displayName: String?,
    ): User? {
        val u = users[id.value] ?: return null
        val updated = u.copy(displayName = displayName)
        users[id.value] = updated
        return updated
    }
}

/** Fake in-memory implementation of [UserSettingsRepo]. */
private class FakeUserSettingsRepo : UserSettingsRepo(ctx = detachedCtx) {
    var settings: UserSettingsRepo.Settings? = null
    var saveNotificationsCalls = 0
    var clearSlackCalls = 0
    var lastCipher: ByteArray? = null
    var lastHint: String? = null

    override fun find(userId: UserId): UserSettingsRepo.Settings? = settings

    override fun saveNotifications(
        userId: UserId,
        notificationEmail: String?,
        slackChannel: String?,
        slackTokenCipher: ByteArray?,
        slackTokenHint: String?,
    ) {
        saveNotificationsCalls++
        val base = settings ?: UserSettingsRepo.Settings(null, null, null, null)
        settings =
            if (slackTokenCipher != null && slackTokenHint != null) {
                lastCipher = slackTokenCipher
                lastHint = slackTokenHint
                base.copy(
                    notificationEmail = notificationEmail,
                    slackChannel = slackChannel,
                    slackTokenCipher = slackTokenCipher,
                    slackTokenHint = slackTokenHint,
                )
            } else {
                base.copy(
                    notificationEmail = notificationEmail,
                    slackChannel = slackChannel,
                )
            }
    }

    override fun clearSlack(userId: UserId) {
        clearSlackCalls++
        settings = settings?.copy(slackTokenCipher = null, slackTokenHint = null)
    }
}

/** Fake Slack client. [authTestResult] determines what authTest returns. */
private class FakeSlackClient(
    private var authTestResult: SlackIdentity? = SlackIdentity("TestTeam", "TestBot"),
    private var postResult: Boolean = true,
    // config = null: a per-user-only transport with NO global Slack configured.
    // The whole suite therefore proves per-user Slack works without global config
    // (the P1 the reviewer flagged).
) : SlackClient(config = null) {
    var authTestCalls = 0
    var lastAuthTestToken: String? = null
    var postCalls = 0
    var lastPostToken: String? = null
    var lastPostChannel: String? = null

    override suspend fun authTest(token: String): SlackIdentity? {
        authTestCalls++
        lastAuthTestToken = token
        return authTestResult
    }

    override suspend fun postMessage(
        token: String,
        channel: String,
        text: String,
        blocks: List<ca.floo.roadtrip.client.slack.SlackBlockDto>?,
        attachments: List<ca.floo.roadtrip.client.slack.SlackAttachmentDto>?,
    ): Boolean {
        postCalls++
        lastPostToken = token
        lastPostChannel = channel
        return postResult
    }
}

/** Fake [EmailDeliveryClient]. [sendResult] controls whether send() succeeds. */
private class FakeEmailDeliveryClient(
    private val sendResult: Boolean = true,
) : EmailDeliveryClient {
    val sentMessages = mutableListOf<EmailDeliveryMessage>()

    override suspend fun send(message: EmailDeliveryMessage): Boolean {
        sentMessages += message
        return sendResult
    }
}

private val fakeEmailConfig = EmailConfig(resendApiKey = "fake-key", from = "no-reply@example.com")

private fun makeEmailService(deliveryClient: EmailDeliveryClient? = FakeEmailDeliveryClient()): EmailNotificationService =
    EmailNotificationService(config = fakeEmailConfig, emailDeliveryClient = deliveryClient)

private fun testUser(
    userId: UserId = testUserId,
    email: String = "user@example.com",
    displayName: String? = "Alice",
    roles: Set<Role> = emptySet(),
): User =
    User(
        id = userId,
        email = email,
        isEmailVerified = true,
        displayName = displayName,
        status = UserStatus.ACTIVE,
        roles = roles,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

private fun makeService(
    userRepo: FakeUserRepo,
    settingsRepo: FakeUserSettingsRepo,
    cipher: SecretCipher? = SecretCipher(testKey),
    slackClient: FakeSlackClient = FakeSlackClient(),
    providerLabel: String? = "Auth0",
    emailService: EmailNotificationService = makeEmailService(),
    appRootUrl: String? = null,
): UserSettingsService =
    UserSettingsService(
        userRepo = userRepo,
        settingsRepo = settingsRepo,
        cipher = cipher,
        slackClient = slackClient,
        providerLabel = providerLabel,
        emailService = emailService,
        appRootUrl = appRootUrl,
    )

class UserSettingsServiceTest {
    private lateinit var userRepo: FakeUserRepo
    private lateinit var settingsRepo: FakeUserSettingsRepo
    private lateinit var slackClient: FakeSlackClient
    private lateinit var settingsService: UserSettingsService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepo()
        settingsRepo = FakeUserSettingsRepo()
        slackClient = FakeSlackClient()
        settingsService = makeService(userRepo, settingsRepo, slackClient = slackClient)
        userRepo.seed(testUser())
    }

    // 1. read(): token never appears; slackConfigured reflects cipher presence; hint passed through.
    @Test
    fun `read never exposes the slack token, only slackConfigured and hint`() {
        val cipher = SecretCipher(testKey)
        val sealed = cipher.seal("xoxb-secret-token")
        settingsRepo.settings =
            UserSettingsRepo.Settings(
                notificationEmail = "notif@example.com",
                slackChannel = "#general",
                slackTokenCipher = sealed,
                slackTokenHint = "oken",
            )

        val dto = settingsService.read(Principal.User(testUserId))

        assertTrue(dto.notifications.slackConfigured)
        assertEquals("oken", dto.notifications.slackTokenHint)
        // Verify the raw token string does not appear anywhere in the serialized DTO shape
        val dtoString = dto.toString()
        assertFalse(dtoString.contains("xoxb-secret-token"))
    }

    @Test
    fun `read shows slackConfigured=false when no cipher stored`() {
        settingsRepo.settings = null

        val dto = settingsService.read(Principal.User(testUserId))

        assertFalse(dto.notifications.slackConfigured)
        assertNull(dto.notifications.slackTokenHint)
    }

    @Test
    fun `read falls back notificationEmail to app_user email when settings null`() {
        settingsRepo.settings = null

        val dto = settingsService.read(Principal.User(testUserId))

        assertEquals("user@example.com", dto.notifications.notificationEmail)
    }

    @Test
    fun `read reflects roles and providerLabel in profile`() {
        userRepo.seed(testUser(roles = setOf(Role.ADMIN)))

        val dto = settingsService.read(Principal.User(testUserId, setOf(Role.ADMIN)))

        assertEquals(listOf(Role.ADMIN.wireValue), dto.profile.roles)
        assertEquals("Auth0", dto.profile.providerLabel)
    }

    // 2. updateProfile(): display name persisted via UserRepo.updateProfile.
    @Test
    fun `updateProfile persists display name via UserRepo`() {
        val dto = settingsService.updateProfile(testUserId, UpdateProfileRequest(displayName = "Bob"))

        assertEquals("Bob", dto.profile.displayName)
        assertEquals("Bob", userRepo.findById(testUserId)?.displayName)
    }

    @Test
    fun `updateProfile with blank name throws InvalidField`() {
        assertFailsWith<SettingsError.InvalidField> {
            settingsService.updateProfile(testUserId, UpdateProfileRequest(displayName = "   "))
        }
    }

    // 3. updateNotifications() with a valid token: authTest called, seal stored, hint == last 4.
    @Test
    fun `updateNotifications with valid token calls authTest, seals token, hint is last 4 chars`() =
        runBlocking {
            val token = "xoxb-test-1234"

            val dto =
                settingsService.updateNotifications(
                    Principal.User(testUserId),
                    UpdateNotificationsRequest(
                        notificationEmail = "notif@example.com",
                        slackChannel = "#alerts",
                        slackToken = token,
                    ),
                )

            assertEquals(1, slackClient.authTestCalls)
            assertEquals(token, slackClient.lastAuthTestToken)
            assertEquals(1, settingsRepo.saveNotificationsCalls)
            assertEquals("1234", settingsRepo.lastHint)
            assertTrue(dto.notifications.slackConfigured)
            assertEquals("1234", dto.notifications.slackTokenHint)
        }

    @Test
    fun `updateNotifications stores notification email and channel`() =
        runBlocking {
            settingsService.updateNotifications(
                Principal.User(testUserId),
                UpdateNotificationsRequest(
                    notificationEmail = "notif@example.com",
                    slackChannel = "#team",
                    slackToken = null,
                ),
            )

            assertEquals(1, settingsRepo.saveNotificationsCalls)
            assertEquals("notif@example.com", settingsRepo.settings?.notificationEmail)
            assertEquals("#team", settingsRepo.settings?.slackChannel)
        }

    // 4. updateNotifications() with a bad token: throws SlackRejected, nothing persisted.
    @Test
    fun `updateNotifications with rejected token throws SlackRejected and persists nothing`() =
        runBlocking {
            slackClient = FakeSlackClient(authTestResult = null)
            settingsService = makeService(userRepo, settingsRepo, slackClient = slackClient)

            assertFailsWith<SettingsError.SlackRejected> {
                settingsService.updateNotifications(
                    Principal.User(testUserId),
                    UpdateNotificationsRequest(
                        notificationEmail = "notif@example.com",
                        slackChannel = "#alerts",
                        slackToken = "xoxb-bad-token",
                    ),
                )
            }

            // Nothing persisted on failure
            assertEquals(0, settingsRepo.saveNotificationsCalls)
        }

    // 5. updateNotifications() with no encryption key (cipher null) + token present: throws EncryptionUnavailable.
    @Test
    fun `updateNotifications with no cipher and token present throws EncryptionUnavailable`() =
        runBlocking {
            settingsService = makeService(userRepo, settingsRepo, cipher = null, slackClient = slackClient)

            assertFailsWith<SettingsError.EncryptionUnavailable> {
                settingsService.updateNotifications(
                    Principal.User(testUserId),
                    UpdateNotificationsRequest(
                        notificationEmail = null,
                        slackChannel = null,
                        slackToken = "xoxb-some-token",
                    ),
                )
            }

            // authTest must not have been called (cipher check comes first)
            assertEquals(0, slackClient.authTestCalls)
            assertEquals(0, settingsRepo.saveNotificationsCalls)
        }

    // 6. updateNotifications() with invalid email: throws InvalidField.
    @Test
    fun `updateNotifications with invalid email throws InvalidField`() =
        runBlocking {
            assertFailsWith<SettingsError.InvalidField> {
                settingsService.updateNotifications(
                    Principal.User(testUserId),
                    UpdateNotificationsRequest(
                        notificationEmail = "not-an-email",
                        slackChannel = null,
                        slackToken = null,
                    ),
                )
            }

            assertEquals(0, settingsRepo.saveNotificationsCalls)
        }

    @Test
    fun `updateNotifications with channel exceeding max length throws InvalidField`() =
        runBlocking {
            val longChannel = "#" + "a".repeat(MAX_SLACK_CHANNEL_CHARS)

            assertFailsWith<SettingsError.InvalidField> {
                settingsService.updateNotifications(
                    Principal.User(testUserId),
                    UpdateNotificationsRequest(
                        notificationEmail = null,
                        slackChannel = longChannel,
                        slackToken = null,
                    ),
                )
            }

            assertEquals(0, settingsRepo.saveNotificationsCalls)
        }

    // 7. disconnectSlack(): repo.clearSlack called; DTO shows slackConfigured=false.
    @Test
    fun `disconnectSlack clears slack token and returns slackConfigured=false`() {
        val cipher = SecretCipher(testKey)
        settingsRepo.settings =
            UserSettingsRepo.Settings(
                notificationEmail = null,
                slackChannel = "#ch",
                slackTokenCipher = cipher.seal("token"),
                slackTokenHint = "oken",
            )

        val dto = settingsService.disconnectSlack(testUserId)

        assertEquals(1, settingsRepo.clearSlackCalls)
        assertFalse(dto.notifications.slackConfigured)
        assertNull(dto.notifications.slackTokenHint)
    }

    // 8. sendSlackTest(): resolves stored token+channel; SlackNotConfigured when no token.
    @Test
    fun `sendSlackTest sends message with stored token and channel`() =
        runBlocking {
            val cipher = SecretCipher(testKey)
            val plainToken = "xoxb-real-token"
            settingsRepo.settings =
                UserSettingsRepo.Settings(
                    notificationEmail = null,
                    slackChannel = "#alerts",
                    slackTokenCipher = cipher.seal(plainToken),
                    slackTokenHint = "oken",
                )

            val result = settingsService.sendSlackTest(testUserId, channelOverride = null)

            assertTrue(result.sent)
            assertEquals("#alerts", result.channel)
            assertEquals(1, slackClient.postCalls)
            assertEquals(plainToken, slackClient.lastPostToken)
            assertEquals("#alerts", slackClient.lastPostChannel)
        }

    @Test
    fun `sendSlackTest respects channelOverride`() =
        runBlocking {
            val cipher = SecretCipher(testKey)
            settingsRepo.settings =
                UserSettingsRepo.Settings(
                    notificationEmail = null,
                    slackChannel = "#stored",
                    slackTokenCipher = cipher.seal("xoxb-token"),
                    slackTokenHint = "oken",
                )

            val result = settingsService.sendSlackTest(testUserId, channelOverride = "#override")

            assertEquals("#override", result.channel)
            assertEquals("#override", slackClient.lastPostChannel)
        }

    @Test
    fun `sendSlackTest throws SlackNotConfigured when no token stored`() =
        runBlocking {
            settingsRepo.settings = null

            assertFailsWith<SettingsError.SlackNotConfigured> {
                settingsService.sendSlackTest(testUserId, channelOverride = null)
            }
            Unit
        }

    @Test
    fun `sendSlackTest throws SlackNotConfigured when service cipher is null even with stored token`() =
        runBlocking {
            // Seed a stored token so the "no token" branch is not the one that fires
            settingsRepo.settings =
                UserSettingsRepo.Settings(
                    notificationEmail = null,
                    slackChannel = "#ch",
                    slackTokenCipher = byteArrayOf(1, 2, 3),
                    slackTokenHint = "hint",
                )
            // Construct service with cipher = null
            val serviceNoCipher = makeService(userRepo, settingsRepo, cipher = null, slackClient = slackClient)

            assertFailsWith<SettingsError.SlackNotConfigured> {
                serviceNoCipher.sendSlackTest(testUserId, channelOverride = null)
            }
            Unit
        }

    @Test
    fun `sendSlackTest throws SlackSendFailed when post returns false`() =
        runBlocking {
            val cipher = SecretCipher(testKey)
            settingsRepo.settings =
                UserSettingsRepo.Settings(
                    notificationEmail = null,
                    slackChannel = "#ch",
                    slackTokenCipher = cipher.seal("xoxb-token"),
                    slackTokenHint = "oken",
                )
            slackClient = FakeSlackClient(postResult = false)
            settingsService = makeService(userRepo, settingsRepo, slackClient = slackClient)

            assertFailsWith<SettingsError.SlackSendFailed> {
                settingsService.sendSlackTest(testUserId, channelOverride = null)
            }
            Unit
        }

    // 9. sendEmailTest(): sends to notification email when set.
    @Test
    fun `sendEmailTest sends to notificationEmail when set`() =
        runBlocking {
            val deliveryClient = FakeEmailDeliveryClient(sendResult = true)
            settingsRepo.settings =
                UserSettingsRepo.Settings(
                    notificationEmail = "notif@example.com",
                    slackChannel = null,
                    slackTokenCipher = null,
                    slackTokenHint = null,
                )
            val emailService = makeEmailService(deliveryClient)
            settingsService = makeService(userRepo, settingsRepo, emailService = emailService)

            val result = settingsService.sendEmailTest(testUserId)

            assertTrue(result.sent)
            assertEquals("notif@example.com", result.recipient)
            assertEquals(1, deliveryClient.sentMessages.size)
            assertEquals("notif@example.com", deliveryClient.sentMessages[0].to)
        }

    @Test
    fun `sendEmailTest falls back to login email when no notificationEmail set`() =
        runBlocking {
            val deliveryClient = FakeEmailDeliveryClient(sendResult = true)
            settingsRepo.settings = null
            val emailService = makeEmailService(deliveryClient)
            settingsService = makeService(userRepo, settingsRepo, emailService = emailService)

            val result = settingsService.sendEmailTest(testUserId)

            assertTrue(result.sent)
            // Falls back to testUser's email
            assertEquals("user@example.com", result.recipient)
            assertEquals(1, deliveryClient.sentMessages.size)
            assertEquals("user@example.com", deliveryClient.sentMessages[0].to)
        }

    @Test
    fun `sendEmailTest throws EmailSendFailed when email is disabled`() =
        runBlocking {
            // null config + null delivery client → email disabled → sendTestEmail returns false
            val emailService = EmailNotificationService(config = null, emailDeliveryClient = null)
            settingsService = makeService(userRepo, settingsRepo, emailService = emailService)

            assertFailsWith<SettingsError.EmailSendFailed> {
                settingsService.sendEmailTest(testUserId)
            }
            Unit
        }

    @Test
    fun `sendEmailTest throws EmailSendFailed when delivery fails`() =
        runBlocking {
            val deliveryClient = FakeEmailDeliveryClient(sendResult = false)
            settingsRepo.settings = null
            val emailService = makeEmailService(deliveryClient)
            settingsService = makeService(userRepo, settingsRepo, emailService = emailService)

            assertFailsWith<SettingsError.EmailSendFailed> {
                settingsService.sendEmailTest(testUserId)
            }
            Unit
        }
}
