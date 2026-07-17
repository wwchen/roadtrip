package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySnapshotsSummaryResponse(
    @SerialName("campsite_id") val campsiteId: Long,
    val stats: List<AvailabilitySnapshotStatsSchema>,
)
