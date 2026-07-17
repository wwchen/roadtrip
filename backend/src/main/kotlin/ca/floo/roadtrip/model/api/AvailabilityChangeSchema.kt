package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityChangeSchema(
    @SerialName("campsite_id") val campsiteId: Long? = null,
    @SerialName("target_date") val targetDate: String,
    @SerialName("observed_from") val observedFrom: String? = null,
    @SerialName("observed_at") val observedAt: String,
    val status: AvailabilityStatus,
    val available: Boolean,
)
