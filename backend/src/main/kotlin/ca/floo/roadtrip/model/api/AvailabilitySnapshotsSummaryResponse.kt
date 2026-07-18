package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySnapshotsSummaryResponse(
    @SerialName("poi_id") val poiId: Long,
    val stats: List<AvailabilitySnapshotStatsSchema>,
)
