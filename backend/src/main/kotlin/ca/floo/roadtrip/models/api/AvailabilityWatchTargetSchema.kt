package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityWatchTargetSchema(
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("campsite_id") val campsiteId: Long? = null,
)
