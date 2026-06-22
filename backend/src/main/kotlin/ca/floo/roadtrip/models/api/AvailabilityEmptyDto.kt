package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityEmptyDto(
    val provider: String = "none",
    val state: String = "empty",
    val summary: String = "No availability provider",
)
