package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CampsiteSummarySchema(
    val id: Long,
    val name: String? = null,
    val loop: String? = null,
    val kind: String? = null,
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    val tags: JsonElement? = null,
    val raw: JsonElement? = null,
)
