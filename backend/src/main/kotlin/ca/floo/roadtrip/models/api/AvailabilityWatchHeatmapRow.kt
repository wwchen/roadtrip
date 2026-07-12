package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchHeatmapRow(
    @SerialName("campsite_id") val campsiteId: Long,
    val name: String? = null,
    val cells: List<AvailabilityWatchHeatmapCell>,
)
