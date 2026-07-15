package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchCapabilitiesDto(
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("booking_actions") val bookingActions: List<String>,
)
