package ca.floo.roadtrip.models.api

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AvailabilityWatchTargetSchema(
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
)

@Serializable
data class AvailabilityWatchCreateRequest(
    // Preferred shape: an explicit target set. When omitted, the legacy
    // single-scope fields below are read as sugar for a one-element list —
    // kept so the existing calendar UI (web/availability/availability-week.js)
    // does not need to change in this PR.
    val targets: List<AvailabilityWatchTargetSchema>? = null,
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    @SerialName("reservable_rid") val reservableRid: String? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject = JsonObject(emptyMap()),
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
    // Same targets-or-legacy-fields shape as create. Absent `targets` AND
    // absent poi_id/reservable_id/reservable_rid means "leave the target set
    // untouched" (maps to UpdateInput.targets = null).
    val targets: List<AvailabilityWatchTargetSchema>? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject? = null,
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
    // Derived convenience fields (first target) so existing consumers
    // (web/availability/availability-week.js reads `w.poi_id`) keep working
    // without a UI change in this PR. New consumers should read `targets`.
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    val reservable: ReservableSchema? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject,
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
    @SerialName("reservable_id") val reservableId: Long,
    @SerialName("reservable_rid") val reservableRid: String,
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
