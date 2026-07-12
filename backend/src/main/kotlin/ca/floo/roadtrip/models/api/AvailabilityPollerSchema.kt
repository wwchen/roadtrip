package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityPollerSchema(
    val id: Long,
    val provider: String,
    @SerialName("parent_ref") val parentRef: String,
    @SerialName("poi_id") val poiId: Long,
    val active: Boolean,
    @SerialName("next_run_at") val nextRunAt: String,
    @SerialName("claimed_until") val claimedUntil: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("attached_watches") val attachedWatches: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)
