package ca.floo.roadtrip.model.metadata.registry

import kotlinx.serialization.Serializable

/**
 * Row in the `campsite_data` section. Same shape as [PoiDataEntry] minus
 * `category` / `subcategory` — campsites aren't map pins, so the FE
 * legend metadata doesn't apply. The terminal etl emits campsite rows
 * via [ca.floo.roadtrip.repo.CampsiteRepo]; the orchestrator dispatches
 * by section, not by etl marker interface.
 */
@Serializable
data class CampsiteDataEntry(
    val name: String,
    val enabled: Boolean = true,
    val etls: List<EtlEntry>,
)
