package ca.floo.roadtrip.models.api

import ca.floo.roadtrip.models.availability.AvailabilityStatus
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

@Serializable
data class AvailabilityPollersListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val pollers: List<AvailabilityPollerSchema>,
)

@Serializable
data class AvailabilityPollersSummary(
    val active: Int,
    val dormant: Int,
    @SerialName("due_now") val dueNow: Int,
    val claimed: Int,
)

/** 200 response for `POST /api/availability/pollers/{id}/force`: the poller was pulled due. */
@Serializable
data class CheckNowResponseDto(
    @SerialName("poller_id") val pollerId: Long,
    @SerialName("next_run_at") val nextRunAt: String,
)

/** 429 response for `POST /api/availability/pollers/{id}/force`: still cooling down. */
@Serializable
data class CheckNowCooldownDto(
    @SerialName("poller_id") val pollerId: Long,
    @SerialName("retry_after_sec") val retryAfterSec: Long,
)

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

@Serializable
data class AvailabilityRunsListResponse(
    val runs: List<AvailabilityRunSchema>,
)

@Serializable
data class AvailabilitySnapshotSchema(
    @SerialName("campsite_id") val campsiteId: Long? = null,
    @SerialName("run_id") val runId: Long? = null,
    @SerialName("target_date") val targetDate: String,
    @SerialName("observed_from") val observedFrom: String? = null,
    @SerialName("observed_at") val observedAt: String,
    val status: AvailabilityStatus,
    val available: Boolean,
)

@Serializable
data class AvailabilitySnapshotsListResponse(
    val snapshots: List<AvailabilitySnapshotSchema>,
)

@Serializable
data class AvailabilitySnapshotStatsSchema(
    @SerialName("target_date") val targetDate: String,
    @SerialName("total_runs") val totalRuns: Int,
    @SerialName("last_open_at") val lastOpenAt: String? = null,
    @SerialName("is_currently_open") val isCurrentlyOpen: Boolean,
    @SerialName("current_or_last_open_window_sec") val currentOrLastOpenWindowSec: Int? = null,
    @SerialName("median_open_window_sec") val medianOpenWindowSec: Int? = null,
    @SerialName("opens_last_24h") val opensLast24h: Int,
)

@Serializable
data class AvailabilitySnapshotsSummaryResponse(
    @SerialName("campsite_id") val campsiteId: Long,
    val stats: List<AvailabilitySnapshotStatsSchema>,
)
