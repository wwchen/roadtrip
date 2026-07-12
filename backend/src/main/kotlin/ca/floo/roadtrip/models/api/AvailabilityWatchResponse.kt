package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchResponse(
    val watch: AvailabilityWatchSchema,
)
