package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
