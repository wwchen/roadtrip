package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SettingsResponseDto(
    val profile: ProfileDto,
    val notifications: NotificationsDto,
)

@Serializable
data class ProfileDto(
    @SerialName("display_name") val displayName: String?,
    @SerialName("login_email") val loginEmail: String,
    @SerialName("is_email_verified") val isEmailVerified: Boolean,
    val roles: List<String>,
    @SerialName("provider_label") val providerLabel: String?,
)

@Serializable
data class NotificationsDto(
    @SerialName("notification_email") val notificationEmail: String?,
    @SerialName("slack_channel") val slackChannel: String?,
    @SerialName("slack_configured") val slackConfigured: Boolean,
    @SerialName("slack_token_hint") val slackTokenHint: String?,
)
