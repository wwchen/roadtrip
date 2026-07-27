package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class SlackTestResponseDto(
    val sent: Boolean,
    val channel: String? = null,
)
