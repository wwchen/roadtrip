package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityJobSchema(
    val id: Long,
    @SerialName("watch_id") val watchId: Long,
    @SerialName("cadence_sec") val cadenceSec: Int,
    val status: String,
    @SerialName("next_run_at") val nextRunAt: String,
    @SerialName("claimed_until") val claimedUntil: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class AvailabilityJobsListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val jobs: List<AvailabilityJobSchema>,
)

@Serializable
data class AvailabilityJobsSummary(
    val active: Int,
    val paused: Int,
    val done: Int,
    @SerialName("due_now") val dueNow: Int,
    val claimed: Int,
)

@Serializable
data class AvailabilityJobRunSchema(
    val id: Long,
    @SerialName("job_id") val jobId: Long,
    val status: String,
    @SerialName("snapshot_count") val snapshotCount: Int,
    @SerialName("duration_ms") val durationMs: Int? = null,
    val error: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class AvailabilityJobRunsListResponse(
    val runs: List<AvailabilityJobRunSchema>,
)

@Serializable
data class AvailabilitySnapshotSchema(
    val id: Long,
    @SerialName("reservable_id") val reservableId: Long? = null,
    @SerialName("run_id") val runId: Long? = null,
    @SerialName("target_date") val targetDate: String,
    @SerialName("observed_at") val observedAt: String,
    val status: String,
    val available: Boolean,
)

@Serializable
data class AvailabilitySnapshotsListResponse(
    val snapshots: List<AvailabilitySnapshotSchema>,
)
