package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// slackToken semantics: null = leave unchanged; non-blank = set/replace.
// Clearing is done via DELETE .../slack, not this request.
@Serializable
data class UpdateNotificationsRequest(
    @SerialName("notification_email") val notificationEmail: String? = null,
    @SerialName("slack_channel") val slackChannel: String? = null,
    @SerialName("slack_token") val slackToken: String? = null,
)
