package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CampsiteSummarySchema(
    val id: Long,
    val rid: String? = null,
    val vendor: String? = null,
    @SerialName("vendor_id") val vendorId: String? = null,
    val name: String? = null,
    val loop: String? = null,
    val kind: String? = null,
    @SerialName("site_type") val siteType: String? = null,
    @SerialName("reservation_url_template") val reservationUrlTemplate: String? = null,
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    @SerialName("provider_ref") val providerRef: JsonElement? = null,
    val tags: JsonElement? = null,
    val raw: JsonElement? = null,
)

@Serializable
data class PoiCampsitesResponseSchema(
    @SerialName("poi_id") val poiId: Long,
    val type: String,
    val campsites: List<CampsiteSummarySchema>,
)

@Serializable
data class PoiCampsitesAvailabilityResponseDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val campsites: List<AvailabilityResponseDto>,
)
