package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.model.api.EmailTestResponseDto
import ca.floo.roadtrip.model.api.NotificationsDto
import ca.floo.roadtrip.model.api.ProfileDto
import ca.floo.roadtrip.model.api.SettingsResponseDto
import ca.floo.roadtrip.model.api.SlackTestResponseDto
import ca.floo.roadtrip.model.api.UpdateNotificationsRequest
import ca.floo.roadtrip.model.api.UpdateProfileRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.security.SecretCipher

const val MAX_SLACK_CHANNEL_CHARS = 255

/** Mirrors the V51 CHECK constraint and `ThemeChoice` in frontend/src/lib/theme.ts. */
@Suppress("TopLevelPropertyNaming")
val THEME_VALUES = setOf("light", "dark", "system")

private val emailRegex = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

/**
 * Domain errors surfaced by [UserSettingsService]. Callers (routes) map these to
 * HTTP status codes; nothing below this layer knows about HTTP.
 */
sealed class SettingsError(
    message: String,
) : RuntimeException(message) {
    class InvalidField(
        message: String,
    ) : SettingsError(message)

    class SlackRejected(
        message: String = "Slack rejected the token",
    ) : SettingsError(message)

    class EncryptionUnavailable(
        message: String = "Encryption key not configured; cannot store Slack token",
    ) : SettingsError(message)

    class SlackNotConfigured(
        message: String = "No Slack token configured for this account",
    ) : SettingsError(message)

    class SlackSendFailed(
        message: String = "Slack message delivery failed",
    ) : SettingsError(message)

    class EmailSendFailed(
        message: String = "Email delivery failed",
    ) : SettingsError(message)

    class RecgovNotConfigured(
        message: String = "No rec.gov credentials are stored for this account",
    ) : SettingsError(message)
}

/**
 * Port: the contract the settings routes depend on. [UserSettingsService]
 * implements it; tests supply lightweight fakes.
 */
interface UserSettingsPort {
    fun read(principal: Principal.User): SettingsResponseDto

    fun updateProfile(
        userId: UserId,
        req: UpdateProfileRequest,
    ): SettingsResponseDto

    suspend fun updateNotifications(
        principal: Principal.User,
        req: UpdateNotificationsRequest,
    ): SettingsResponseDto

    fun disconnectSlack(userId: UserId): SettingsResponseDto

    suspend fun sendSlackTest(
        userId: UserId,
        channelOverride: String?,
    ): SlackTestResponseDto

    suspend fun sendEmailTest(userId: UserId): EmailTestResponseDto
}

/**
 * Orchestration layer for account settings.
 *
 * Reads and writes [UserRepo] (profile) and [UserSettingsRepo] (notification
 * preferences + Slack token). The Slack token is sealed at rest via [cipher]; a
 * null cipher means the encryption key is not configured and token storage is
 * disabled. The [slackClient] is used only when a token must be validated or a
 * test message sent.
 */
class UserSettingsService(
    private val userRepo: UserRepo,
    private val settingsRepo: UserSettingsRepo,
    private val cipher: SecretCipher?,
    // Always available and independent of the global Slack config — the per-user
    // methods carry their own token (see [ServiceModule]).
    private val slackClient: SlackClient,
    private val providerLabel: String?,
    private val emailService: EmailNotificationService,
    private val appRootUrl: String? = null,
) : UserSettingsPort {
    /**
     * Assembles a full [SettingsResponseDto] for the given principal. The Slack
     * token is NEVER included — only [NotificationsDto.slackConfigured] and
     * [NotificationsDto.slackTokenHint] are surfaced.
     *
     * [NotificationsDto.notificationEmail] falls back to the user's login email
     * when no override is stored.
     */
    override fun read(principal: Principal.User): SettingsResponseDto {
        val user = requireNotNull(userRepo.findById(principal.userId)) { "user not found: ${principal.userId}" }
        val settings = settingsRepo.find(principal.userId)
        return assembleDto(user, settings, principal)
    }

    /**
     * Updates the display name and returns the refreshed settings.
     *
     * Throws [SettingsError.InvalidField] when [req.displayName] is blank (but
     * non-null) — a blank name would silently erase the user's chosen name.
     */
    override fun updateProfile(
        userId: UserId,
        req: UpdateProfileRequest,
    ): SettingsResponseDto {
        if (req.displayName != null && req.displayName.isBlank()) {
            throw SettingsError.InvalidField("display_name must not be blank")
        }
        if (req.theme != null && req.theme !in THEME_VALUES) {
            throw SettingsError.InvalidField("theme must be one of ${THEME_VALUES.joinToString(", ")}")
        }
        val user =
            requireNotNull(userRepo.updateProfile(userId, req.displayName, req.theme)) {
                "user not found: $userId"
            }
        val settings = settingsRepo.find(userId)
        val principal = Principal.User(userId, user.roles)
        return assembleDto(user, settings, principal)
    }

    /**
     * Validates and persists notification preferences. When a [slackToken] is
     * supplied:
     * 1. Requires the cipher to be configured; throws [SettingsError.EncryptionUnavailable] otherwise.
     * 2. Calls [SlackClient.authTest]; throws [SettingsError.SlackRejected] when Slack rejects.
     * 3. Seals the token and persists it with hint = last 4 characters.
     *
     * Validation and Slack calls happen BEFORE any persistence so that a failure
     * leaves the stored state unchanged.
     */
    override suspend fun updateNotifications(
        principal: Principal.User,
        req: UpdateNotificationsRequest,
    ): SettingsResponseDto {
        // Validate email format if provided
        if (req.notificationEmail != null && !emailRegex.matches(req.notificationEmail)) {
            throw SettingsError.InvalidField("notification_email is not a valid email address")
        }
        // Validate channel length if provided
        if (req.slackChannel != null && req.slackChannel.length > MAX_SLACK_CHANNEL_CHARS) {
            throw SettingsError.InvalidField("slack_channel exceeds $MAX_SLACK_CHANNEL_CHARS characters")
        }

        val newTokenCipher: ByteArray?
        val newTokenHint: String?
        if (!req.slackToken.isNullOrBlank()) {
            // Require cipher before calling Slack (fail fast without a network call)
            val c = cipher ?: throw SettingsError.EncryptionUnavailable()
            slackClient.authTest(req.slackToken) ?: throw SettingsError.SlackRejected()
            newTokenCipher = c.seal(req.slackToken)
            newTokenHint = req.slackToken.takeLast(4)
        } else {
            newTokenCipher = null
            newTokenHint = null
        }

        // All validations passed — persist atomically
        settingsRepo.saveNotifications(
            userId = principal.userId,
            notificationEmail = req.notificationEmail,
            slackChannel = req.slackChannel,
            slackTokenCipher = newTokenCipher,
            slackTokenHint = newTokenHint,
        )

        val user = requireNotNull(userRepo.findById(principal.userId)) { "user not found: ${principal.userId}" }
        val settings = settingsRepo.find(principal.userId)
        return assembleDto(user, settings, principal)
    }

    /**
     * Clears stored Slack credentials and returns the refreshed settings.
     */
    override fun disconnectSlack(userId: UserId): SettingsResponseDto {
        settingsRepo.clearSlack(userId)
        val user = requireNotNull(userRepo.findById(userId)) { "user not found: $userId" }
        val settings = settingsRepo.find(userId)
        val principal = Principal.User(userId, user.roles)
        return assembleDto(user, settings, principal)
    }

    /**
     * Sends a test Slack message using the stored token. The channel comes from
     * [channelOverride] if present, otherwise the stored channel.
     *
     * Throws [SettingsError.SlackNotConfigured] when no token is stored.
     * Throws [SettingsError.SlackSendFailed] when the message delivery fails.
     */
    override suspend fun sendSlackTest(
        userId: UserId,
        channelOverride: String?,
    ): SlackTestResponseDto {
        val c = cipher ?: throw SettingsError.SlackNotConfigured("Encryption not configured")
        val settings = settingsRepo.find(userId)
        val tokenCipher = settings?.slackTokenCipher ?: throw SettingsError.SlackNotConfigured()

        val plainToken = c.open(tokenCipher)
        val channel = channelOverride ?: settings.slackChannel

        val sent = slackClient.postMessage(token = plainToken, channel = channel ?: "", text = "Roadtrip test message")
        if (!sent) throw SettingsError.SlackSendFailed()
        return SlackTestResponseDto(sent = true, channel = channel)
    }

    /**
     * Sends a test email to the user's notification email (falling back to login
     * email when no override is stored).
     *
     * Throws [SettingsError.EmailSendFailed] when email is disabled or delivery fails.
     */
    override suspend fun sendEmailTest(userId: UserId): EmailTestResponseDto {
        val settings = settingsRepo.find(userId)
        val recipient =
            settings?.notificationEmail
                ?: requireNotNull(userRepo.findById(userId)) { "user not found: $userId" }.email
        val sent = emailService.sendTestEmail(listOf(recipient), appRootUrl)
        if (!sent) throw SettingsError.EmailSendFailed()
        return EmailTestResponseDto(sent = true, recipient = recipient)
    }

    // --- private helpers ---

    private fun assembleDto(
        user: User,
        settings: UserSettingsRepo.Settings?,
        principal: Principal.User,
    ): SettingsResponseDto {
        val notificationEmail = settings?.notificationEmail ?: user.email
        return SettingsResponseDto(
            profile =
                ProfileDto(
                    displayName = user.displayName,
                    loginEmail = user.email,
                    isEmailVerified = user.isEmailVerified,
                    roles = principal.roles.map { it.wireValue },
                    providerLabel = providerLabel,
                    theme = user.theme,
                ),
            notifications =
                NotificationsDto(
                    notificationEmail = notificationEmail,
                    slackChannel = settings?.slackChannel,
                    slackConfigured = settings?.slackTokenCipher != null,
                    slackTokenHint = settings?.slackTokenHint,
                ),
            booking = bookingSettingsDto(settings, cipher),
        )
    }
}
