package ca.floo.roadtrip.models.api

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchHeatmapCell(
    @SerialName("target_date") val targetDate: String,
    val status: AvailabilityStatus? = null,
    val available: Boolean? = null,
    @SerialName("observed_at") val observedAt: String? = null,
)
