package ca.floo.roadtrip.clients.slack

import kotlinx.serialization.Serializable

@Serializable
data class SlackTextDto(
    val type: String,
    val text: String,
    val emoji: Boolean? = null,
)
