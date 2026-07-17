package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.json.JsonObject
import java.time.Instant

// ---- DTO + per-source strategies ------------------------------------------

data class AspiraJoinDto(
    val leaves: AspiraLeavesPayload,
    val geomSources: List<Pair<String, GeometrySource>>,
    /** Per-park `/api/resourcelocation/resources` envelopes, empty when the tenant declares no inventory input. */
    val inventoryEnvelopes: List<ca.floo.roadtrip.model.metadata.Envelope> = emptyList(),
    /** Tenant `/api/resourcecategory` dictionary payload, null when not declared. */
    val dictionaryPayload: JsonObject? = null,
    val fetchedAt: Instant,
)
