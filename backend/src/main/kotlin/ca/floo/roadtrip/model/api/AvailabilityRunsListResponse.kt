package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityRunsListResponse(
    val runs: List<AvailabilityRunSchema>,
)
