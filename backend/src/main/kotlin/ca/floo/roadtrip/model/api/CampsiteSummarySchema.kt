package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CampsiteSummarySchema(
    val id: Long,
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
