package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiRow
import kotlinx.serialization.json.JsonObject

data class BcParksCampgroundsDto(
    val leaves: List<AspiraLeaf>,
    val strapiRows: List<BcParksStrapiRow>,
    val inventoryEnvelopes: List<Envelope>,
    val dictionaryPayload: JsonObject?,
)
