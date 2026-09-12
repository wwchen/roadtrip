package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

/** Body of `POST /api/settings/notifications/slack/test`. A null channel means the stored one. */
@Serializable
data class SlackTestRequest(
    val channel: String? = null,
)
