package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SettingsResponseDto(
    val profile: ProfileDto,
    val notifications: NotificationsDto,
    val booking: BookingSettingsDto,
)

/**
 * The stored rec.gov credential summary — a pure database read, which is why it
 * rides in the settings document. Live session health is deliberately NOT here:
 * it comes from `GET /api/settings/recgov/status` so opening Settings never
 * waits on the companion.
 *
 * The password itself never appears in any response; only whether one is stored
 * and its last-4 hint.
 */
@Serializable
data class BookingSettingsDto(
    @SerialName("recgov_configured") val recgovConfigured: Boolean,
    @SerialName("recgov_username") val recgovUsername: String?,
    @SerialName("recgov_password_hint") val recgovPasswordHint: String?,
)

@Serializable
data class ProfileDto(
    @SerialName("display_name") val displayName: String?,
    @SerialName("login_email") val loginEmail: String,
    @SerialName("is_email_verified") val isEmailVerified: Boolean,
    val roles: List<String>,
    @SerialName("provider_label") val providerLabel: String?,
    val theme: String,
)

@Serializable
data class NotificationsDto(
    @SerialName("notification_email") val notificationEmail: String?,
    @SerialName("slack_channel") val slackChannel: String?,
    @SerialName("slack_configured") val slackConfigured: Boolean,
    @SerialName("slack_token_hint") val slackTokenHint: String?,
)
