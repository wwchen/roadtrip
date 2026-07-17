package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityEmptyDto(
    val provider: String = "none",
    val state: String = "empty",
    val summary: String = "No availability provider",
)
