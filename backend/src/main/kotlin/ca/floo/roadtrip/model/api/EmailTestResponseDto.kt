package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class EmailTestResponseDto(
    val sent: Boolean,
    val recipient: String? = null,
)
