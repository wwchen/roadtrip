package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilitySnapshotStatsSchema(
    @SerialName("target_date") val targetDate: String,
    @SerialName("total_runs") val totalRuns: Int,
    @SerialName("first_run_at") val firstRunAt: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("median_cadence_sec") val medianCadenceSec: Int? = null,
    @SerialName("last_open_at") val lastOpenAt: String? = null,
    @SerialName("is_currently_open") val isCurrentlyOpen: Boolean,
    @SerialName("min_open_window_sec") val minOpenWindowSec: Int? = null,
    @SerialName("max_open_window_sec") val maxOpenWindowSec: Int? = null,
)
