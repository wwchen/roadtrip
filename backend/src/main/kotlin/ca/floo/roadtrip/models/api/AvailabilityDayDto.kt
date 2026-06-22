package ca.floo.roadtrip.models.api

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityDayDto(
    val date: String,
    val status: AvailabilityStatus,
    @SerialName("available_reservable_ids") val availableReservableIds: List<String>? = null,
    @SerialName("reservable_statuses") val reservableStatuses: Map<String, AvailabilityStatus>? = null,
)
