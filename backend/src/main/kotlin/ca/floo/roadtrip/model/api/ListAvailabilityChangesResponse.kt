package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class ListAvailabilityChangesResponse(
    val changes: List<AvailabilityChangeSchema>,
)
