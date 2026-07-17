package ca.floo.roadtrip.model.metadata.registry

import kotlinx.serialization.Serializable

/**
 * Row in the `campsite_parent_joiner` section. Names a single adapter that
 * recomputes each campsite's campground parent from vendor refs and
 * reparents rows whose current `campsites.campground_id` disagrees. No etl
 * chain; joiners don't transform raw data, they query DB tables.
 *
 * `args` follows the same shape as [EtlEntry.args]: free-form
 * adapter-specific config (e.g. which provider source to scope to).
 */
@Serializable
data class CampsiteParentJoinerEntry(
    val name: String,
    val enabled: Boolean = true,
    val adapter: String,
    val args: Map<String, String> = emptyMap(),
)
