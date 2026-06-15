package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ReservableSchema(
    val rid: String,
    val type: String,
    val vendor: String,
    @SerialName("vendor_id") val vendorId: String,
    val name: String? = null,
    val loop: String? = null,
    @SerialName("site_type") val siteType: String? = null,
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
data class ReservableAvailabilityMonitorCreateRequestSchema(
    val cadence: Int,
    @SerialName("trigger_action") val triggerAction: String,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = true,
)

@Serializable
data class ReservableAvailabilityMonitorSchema(
    val id: Long,
    val reservable: ReservableSchema,
    val cadence: Int,
    @SerialName("trigger_action") val triggerAction: String,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean,
    val status: String,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_triggered_at") val lastTriggeredAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ReservableAvailabilityMonitorResponseSchema(
    val monitor: ReservableAvailabilityMonitorSchema,
)

@Serializable
data class ReservableAvailabilityMonitorListResponseSchema(
    val monitors: List<ReservableAvailabilityMonitorSchema>,
)
