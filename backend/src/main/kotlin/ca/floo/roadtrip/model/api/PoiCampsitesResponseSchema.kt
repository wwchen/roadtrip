package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.domain.Campsite
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PoiCampsitesResponseSchema(
    @SerialName("poi_id") val poiId: Long,
    val type: String,
    val campsites: List<Campsite>,
    @SerialName("reservation_url_templates") val reservationUrlTemplates: Map<Long, String> = emptyMap(),
)
