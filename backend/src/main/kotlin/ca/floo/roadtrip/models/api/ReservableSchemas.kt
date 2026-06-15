package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ReservableSchema(
    val rid: String,
    val type: String,
    val vendor: String,
    @SerialName("vendor_id") val vendorId: String,
    val name: String? = null,
    val loop: String? = null,
    @SerialName("site_type") val siteType: String? = null,
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    val raw: JsonElement? = null,
)

@Serializable
data class ReservablesResponseSchema(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val reservables: List<ReservableSchema>,
)

@Serializable
data class ReservableDetailResponseSchema(
    val reservable: ReservableSchema,
    @SerialName("poi_ids") val poiIds: List<Long>,
)

@Serializable
data class PoiReservablesResponseSchema(
    @SerialName("poi_id") val poiId: Long,
    val type: String,
    @SerialName("total_at_poi") val totalAtPoi: Int,
    val reservables: List<ReservableSchema>,
)

@Serializable
data class ReservableAvailabilityScopeSchema(
    @SerialName("poi_id") val poiId: Long? = null,
    val rid: String? = null,
)

@Serializable
data class ReservableAvailabilityFiltersSchema(
    val type: List<String> = emptyList(),
    val vendor: List<String> = emptyList(),
    @SerialName("vendor_id") val vendorId: List<String> = emptyList(),
    val name: List<String> = emptyList(),
    val loop: List<String> = emptyList(),
    @SerialName("site_type") val siteType: List<String> = emptyList(),
    val raw: JsonElement? = null,
)

@Serializable
data class ReservableAvailabilityQueryRequestSchema(
    val scope: ReservableAvailabilityScopeSchema,
    @SerialName("reservable_filters") val reservableFilters: ReservableAvailabilityFiltersSchema =
        ReservableAvailabilityFiltersSchema(),
    @SerialName("start_date") val startDate: String,
    val days: Int,
    @SerialName("min_nights") val minNights: Int = 1,
    val force: Boolean = false,
)

@Serializable
data class ReservableAvailabilityResultSchema(
    val reservable: ReservableSchema,
    @SerialName("matching_starts") val matchingStarts: List<String>,
    @SerialName("partial_starts") val partialStarts: List<String>,
)

@Serializable
data class ReservableAvailabilityQueryResponseSchema(
    @SerialName("run_id") val runId: Long,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("candidate_count") val candidateCount: Int,
    @SerialName("log_count") val logCount: Int,
    val candidates: List<ReservableSchema>,
    val results: List<ReservableAvailabilityResultSchema>,
)

@Serializable
data class ReservableAvailabilityPollerCreateRequestSchema(
    val scope: ReservableAvailabilityScopeSchema = ReservableAvailabilityScopeSchema(),
    @SerialName("reservable_filters") val reservableFilters: ReservableAvailabilityFiltersSchema =
        ReservableAvailabilityFiltersSchema(),
    @SerialName("target_dates") val targetDates: List<String>,
    @SerialName("min_nights") val minNights: Int = 1,
    val cadence: Int,
    @SerialName("trigger_actions") val triggerActions: JsonArray,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = true,
    val force: Boolean = false,
)

@Serializable
data class ReservableAvailabilityPollerPatchRequestSchema(
    val status: String? = null,
    val cadence: Int? = null,
    @SerialName("target_dates") val targetDates: List<String>? = null,
    @SerialName("trigger_actions") val triggerActions: JsonArray? = null,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean? = null,
)

@Serializable
data class ReservableAvailabilityPollerSchema(
    val id: Long,
    val scope: ReservableAvailabilityScopeSchema,
    @SerialName("reservable_filters") val reservableFilters: JsonObject,
    @SerialName("target_dates") val targetDates: List<String>,
    @SerialName("min_nights") val minNights: Int,
    val cadence: Int,
    @SerialName("trigger_actions") val triggerActions: JsonArray,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean,
    val status: String,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_triggered_at") val lastTriggeredAt: String? = null,
    @SerialName("next_poll_after") val nextPollAfter: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ReservableAvailabilityRunSchema(
    val id: Long,
    @SerialName("source_kind") val sourceKind: String,
    @SerialName("poller_id") val pollerId: Long? = null,
    val status: String,
    @SerialName("candidate_count") val candidateCount: Int,
    @SerialName("log_count") val logCount: Int,
    val error: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class ReservableAvailabilityPollerResponseSchema(
    val poller: ReservableAvailabilityPollerSchema,
    @SerialName("initial_run") val initialRun: ReservableAvailabilityRunSchema? = null,
)

@Serializable
data class ReservableAvailabilityPollerListResponseSchema(
    val pollers: List<ReservableAvailabilityPollerSchema>,
)

@Serializable
data class ReservableAvailabilityRunListResponseSchema(
    val runs: List<ReservableAvailabilityRunSchema>,
)

@Serializable
data class ReservableAvailabilityLogSchema(
    val id: Long,
    @SerialName("run_id") val runId: Long? = null,
    @SerialName("reservable_rid") val reservableRid: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("target_date") val targetDate: String,
    val status: String,
    val available: Boolean,
    @SerialName("day_payload") val dayPayload: JsonObject,
)

@Serializable
data class ReservableAvailabilityLogListResponseSchema(
    val logs: List<ReservableAvailabilityLogSchema>,
)
