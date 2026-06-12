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
