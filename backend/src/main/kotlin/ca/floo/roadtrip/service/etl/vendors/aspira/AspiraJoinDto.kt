package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class AspiraJoinDto(
    val leaves: List<AspiraLeaf>,
    val geomSources: List<Pair<String, GeometrySource>>,
    val inventoryEnvelopes: List<Envelope> = emptyList(),
    val dictionaryPayload: JsonObject? = null,
    val fetchedAt: Instant,
)
