package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
internal data class HealthResponseDto(
    val status: String = "ok",
    val now: Long,
)
