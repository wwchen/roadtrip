package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityDayDto(
    val date: String,
    val status: AvailabilityStatus,
    @SerialName("available_campsite_ids") val availableCampsiteIds: List<Long>? = null,
    @SerialName("campsite_statuses") val campsiteStatuses: Map<Long, AvailabilityStatus>? = null,
)
