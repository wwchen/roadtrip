package ca.floo.roadtrip.models.api

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AvailabilityWatchTargetSchema(
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("campsite_id") val campsiteId: Long? = null,
)

@Serializable
data class AvailabilityWatchCreateRequest(
    val targets: List<AvailabilityWatchTargetSchema>? = null,
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("campsite_id") val campsiteId: Long? = null,
    @SerialName("campsite_filters") val campsiteFilters: JsonObject = JsonObject(emptyMap()),
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    // NULL means "no watch-level cadence override" — the resolver falls
    // through to the POI override, then the global default.
    @SerialName("cadence_sec") val cadenceSec: Int? = null,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject = JsonObject(emptyMap()),
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = true,
)

@Serializable
data class AvailabilityWatchUpdateRequest(
    val targets: List<AvailabilityWatchTargetSchema>? = null,
    @SerialName("campsite_filters") val campsiteFilters: JsonObject? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("cadence_sec") val cadenceSec: Int? = null,
    @SerialName("trigger_kinds") val triggerKinds: List<String>? = null,
    @SerialName("trigger_config") val triggerConfig: JsonObject? = null,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class AvailabilityWatchSchema(
    val id: Long,
    val targets: List<AvailabilityWatchTargetSchema>,
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("campsite_id") val campsiteId: Long? = null,
    val campsite: CampsiteSummarySchema? = null,
    @SerialName("campsite_filters") val campsiteFilters: JsonObject,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    // NULL when the watch carries no cadence override (falls through to the
    // POI override / global default).
    @SerialName("cadence_sec") val cadenceSec: Int? = null,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    // Freshness/error of the most recent poll run across this watch's
    // poller(s). All null when no run has happened yet. `lastRunAt` is the
    // run's completed_at (null while a run is still in flight). Read-only —
    // sourced from availability_run, never accepted on create/update.
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("last_run_status") val lastRunStatus: String? = null,
    @SerialName("last_run_error") val lastRunError: String? = null,
)

@Serializable
data class AvailabilityWatchResponse(
    val watch: AvailabilityWatchSchema,
)

@Serializable
data class AvailabilityWatchListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val watches: List<AvailabilityWatchSchema>,
)

@Serializable
data class AvailabilityWatchHeatmapCell(
    @SerialName("target_date") val targetDate: String,
    val status: AvailabilityStatus? = null,
    val available: Boolean? = null,
    @SerialName("observed_at") val observedAt: String? = null,
)

@Serializable
data class AvailabilityWatchHeatmapRow(
    @SerialName("campsite_id") val campsiteId: Long,
    val name: String? = null,
    val cells: List<AvailabilityWatchHeatmapCell>,
)

@Serializable
data class AvailabilityWatchHeatmapGroup(
    val loop: String? = null,
    val rows: List<AvailabilityWatchHeatmapRow>,
)

@Serializable
data class AvailabilityWatchHeatmapResponse(
    @SerialName("watch_id") val watchId: Long,
    val dates: List<String>,
    val groups: List<AvailabilityWatchHeatmapGroup>,
)
