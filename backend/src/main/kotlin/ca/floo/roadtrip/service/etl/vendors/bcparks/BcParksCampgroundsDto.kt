package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.BcParksStrapiRow
import ca.floo.roadtrip.service.etl.vendors.aspira.GeometrySource
import kotlinx.serialization.json.JsonObject

data class BcParksCampgroundsDto(
    val leaves: List<AspiraLeaf>,
    val strapiRows: List<BcParksStrapiRow>,
    /** The single declared Strapi source, paired with its input slug, for [GeometrySource] indexing. */
    val geomSources: List<Pair<String, GeometrySource>>,
    val inventoryEnvelopes: List<Envelope>,
    val dictionaryPayload: JsonObject?,
)
