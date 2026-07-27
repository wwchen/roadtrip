package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.client.slack.SlackClient
import ca.floo.roadtrip.model.api.NotificationsDto
import ca.floo.roadtrip.model.api.ProfileDto
import ca.floo.roadtrip.model.api.SettingsResponseDto
import ca.floo.roadtrip.model.api.SlackTestResponseDto
import ca.floo.roadtrip.model.api.UpdateNotificationsRequest
import ca.floo.roadtrip.model.api.UpdateProfileRequest
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.security.SecretCipher

const val MAX_SLACK_CHANNEL_CHARS = 255

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
    private val slackClient: SlackClient?,
    private val providerLabel: String?,
) {
    /**
     * Assembles a full [SettingsResponseDto] for the given principal. The Slack
     * token is NEVER included — only [NotificationsDto.slackConfigured] and
     * [NotificationsDto.slackTokenHint] are surfaced.
     *
     * [NotificationsDto.notificationEmail] falls back to the user's login email
     * when no override is stored.
     */
    fun read(principal: Principal.User): SettingsResponseDto {
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
    fun updateProfile(
        userId: UserId,
        req: UpdateProfileRequest,
    ): SettingsResponseDto {
        if (req.displayName != null && req.displayName.isBlank()) {
            throw SettingsError.InvalidField("display_name must not be blank")
        }
        val user = requireNotNull(userRepo.updateProfile(userId, req.displayName)) { "user not found: $userId" }
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
    suspend fun updateNotifications(
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
            val client = requireNotNull(slackClient) { "Slack client not configured" }
            val identity = client.authTest(req.slackToken) ?: throw SettingsError.SlackRejected()
            // identity confirmed; we don't use the details here — just verifying the token is valid
            newTokenCipher = c.seal(req.slackToken)
            newTokenHint = req.slackToken.takeLast(4)
        } else {
            newTokenCipher = null
            newTokenHint = null
        }

        // All validations passed — persist
        settingsRepo.upsertNotifications(principal.userId, req.notificationEmail, req.slackChannel)
        if (newTokenCipher != null && newTokenHint != null) {
            settingsRepo.setSlackToken(principal.userId, newTokenCipher, newTokenHint)
        }

        val user = requireNotNull(userRepo.findById(principal.userId)) { "user not found: ${principal.userId}" }
        val settings = settingsRepo.find(principal.userId)
        return assembleDto(user, settings, principal)
    }

    /**
     * Clears stored Slack credentials and returns the refreshed settings.
     */
    fun disconnectSlack(userId: UserId): SettingsResponseDto {
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
    suspend fun sendSlackTest(
        userId: UserId,
        channelOverride: String?,
    ): SlackTestResponseDto {
        val c = cipher ?: throw SettingsError.SlackNotConfigured("Encryption not configured")
        val settings = settingsRepo.find(userId)
        val tokenCipher = settings?.slackTokenCipher ?: throw SettingsError.SlackNotConfigured()
        val client = requireNotNull(slackClient) { "Slack client not configured" }

        val plainToken = c.open(tokenCipher)
        val channel = channelOverride ?: settings.slackChannel

        val sent = client.postMessage(token = plainToken, channel = channel ?: "", text = "Roadtrip test message")
        if (!sent) throw SettingsError.SlackSendFailed()
        return SlackTestResponseDto(sent = true, channel = channel)
    }

    // --- private helpers ---

    private fun assembleDto(
        user: UserRepo.User,
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
                ),
            notifications =
                NotificationsDto(
                    notificationEmail = notificationEmail,
                    slackChannel = settings?.slackChannel,
                    slackConfigured = settings?.slackTokenCipher != null,
                    slackTokenHint = settings?.slackTokenHint,
                ),
        )
    }
}
