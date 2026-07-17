package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityChangesListResponse(
    val changes: List<AvailabilityChangeSchema>,
)
