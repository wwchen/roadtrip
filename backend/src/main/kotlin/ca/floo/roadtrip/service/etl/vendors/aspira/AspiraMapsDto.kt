package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.json.JsonArray

/** Just-parsed envelope, before we walk the tree. */
data class AspiraMapsDto(
    val maps: JsonArray,
) {
    val isEmpty: Boolean get() = maps.isEmpty()
}
