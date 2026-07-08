package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ReservableSchema(
    val id: Long,
    val type: String,
    val vendor: String,
    @SerialName("vendor_id") val vendorId: String,
    val name: String? = null,
    val loop: String? = null,
    @SerialName("site_type") val siteType: String? = null,
    @SerialName("reservation_url_template") val reservationUrlTemplate: String? = null,
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    @SerialName("provider_ref") val providerRef: JsonElement? = null,
    val tags: JsonElement? = null,
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
    val reservables: List<ReservableSchema>,
)
