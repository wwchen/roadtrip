package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PoiCampsitesResponseSchema(
    @SerialName("poi_id") val poiId: Long,
    val type: String,
    val campsites: List<CampsiteSummarySchema>,
)
