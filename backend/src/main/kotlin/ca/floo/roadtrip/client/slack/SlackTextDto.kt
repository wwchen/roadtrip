package ca.floo.roadtrip.client.slack

import kotlinx.serialization.Serializable

@Serializable
data class SlackTextDto(
    val type: String,
    val text: String,
    val emoji: Boolean? = null,
)
