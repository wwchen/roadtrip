package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityChangeSchema(
    @SerialName("campsite_id") val campsiteId: Long? = null,
    @SerialName("campsite_name") val campsiteName: String? = null,
    @SerialName("target_date") val targetDate: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("from_status") val fromStatus: AvailabilityStatus? = null,
    @SerialName("to_status") val toStatus: AvailabilityStatus,
)
