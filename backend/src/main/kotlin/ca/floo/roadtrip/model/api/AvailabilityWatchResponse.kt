package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchResponse(
    val watch: AvailabilityWatchSchema,
    @SerialName("watch_capabilities") val watchCapabilities: AvailabilityWatchCapabilitiesDto? = null,
)
