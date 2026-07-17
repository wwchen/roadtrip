package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

// "kind" tells the FE which visual treatment to use:
//   - "reserve" — booking flow (rec.gov campground page, Aspira homepage, …)
//   - "info"    — informational page (FS recarea, BC Parks, planet fitness)
@Serializable
data class PoiCtaSchema(
    val url: String,
    val label: String,
    val kind: String,
)
