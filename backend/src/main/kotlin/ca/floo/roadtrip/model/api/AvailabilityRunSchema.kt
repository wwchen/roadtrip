package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityRunSchema(
    val id: Long,
    @SerialName("poller_id") val pollerId: Long,
    val status: String,
    @SerialName("snapshot_count") val snapshotCount: Int,
    @SerialName("duration_ms") val durationMs: Int? = null,
    val error: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
)
