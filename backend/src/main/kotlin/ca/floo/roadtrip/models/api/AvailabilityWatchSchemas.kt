package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AvailabilityWatchCreateRequest(
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject = JsonObject(emptyMap()),
    @SerialName("target_dates") val targetDates: List<String>,
    @SerialName("min_nights") val minNights: Int = 1,
    @SerialName("cadence_sec") val cadenceSec: Int,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject = JsonObject(emptyMap()),
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = true,
)

@Serializable
data class AvailabilityWatchUpdateRequest(
    @SerialName("reservable_filters") val reservableFilters: JsonObject? = null,
    @SerialName("target_dates") val targetDates: List<String>? = null,
    @SerialName("min_nights") val minNights: Int? = null,
    @SerialName("cadence_sec") val cadenceSec: Int? = null,
    @SerialName("trigger_kinds") val triggerKinds: List<String>? = null,
    @SerialName("trigger_config") val triggerConfig: JsonObject? = null,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class AvailabilityWatchSchema(
    val id: Long,
    @SerialName("poi_id") val poiId: Long? = null,
    @SerialName("reservable_id") val reservableId: Long? = null,
    val reservable: ReservableSchema? = null,
    @SerialName("reservable_filters") val reservableFilters: JsonObject,
    @SerialName("target_dates") val targetDates: List<String>,
    @SerialName("min_nights") val minNights: Int,
    @SerialName("cadence_sec") val cadenceSec: Int,
    @SerialName("trigger_kinds") val triggerKinds: List<String>,
    @SerialName("trigger_config") val triggerConfig: JsonObject,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
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
