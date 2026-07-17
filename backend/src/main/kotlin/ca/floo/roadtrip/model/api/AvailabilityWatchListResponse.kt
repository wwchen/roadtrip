package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val watches: List<AvailabilityWatchSchema>,
)
