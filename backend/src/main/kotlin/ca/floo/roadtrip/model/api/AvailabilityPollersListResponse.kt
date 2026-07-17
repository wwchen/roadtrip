package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityPollersListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val pollers: List<AvailabilityPollerSchema>,
)
