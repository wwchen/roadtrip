package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class BcParksMergeDto(
    val leaves: List<AspiraLeaf>,
    val strapiRows: List<BcParksStrapiRow>,
    val strapiEnvelopes: List<Envelope>,
    val inventoryEnvelopes: List<Envelope>,
    val dictionaryPayload: JsonObject?,
    val mapsArray: JsonArray,
)
