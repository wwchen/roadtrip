package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityRunsListResponse(
    val runs: List<AvailabilityRunSchema>,
)
