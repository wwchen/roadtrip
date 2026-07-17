package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySnapshotStatsSchema(
    @SerialName("target_date") val targetDate: String,
    @SerialName("total_runs") val totalRuns: Int,
    @SerialName("last_open_at") val lastOpenAt: String? = null,
    @SerialName("is_currently_open") val isCurrentlyOpen: Boolean,
    @SerialName("current_or_last_open_window_sec") val currentOrLastOpenWindowSec: Int? = null,
    @SerialName("median_open_window_sec") val medianOpenWindowSec: Int? = null,
    @SerialName("opens_last_24h") val opensLast24h: Int,
)
