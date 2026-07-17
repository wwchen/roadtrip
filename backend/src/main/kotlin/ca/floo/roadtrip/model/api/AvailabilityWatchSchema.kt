package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
