package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchHeatmapGroup(
    val loop: String? = null,
    val rows: List<AvailabilityWatchHeatmapRow>,
)
