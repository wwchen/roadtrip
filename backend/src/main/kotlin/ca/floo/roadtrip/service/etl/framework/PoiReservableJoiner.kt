package ca.floo.roadtrip.service.etl.framework

import org.jooq.DSLContext

/**
 * Disabled legacy import hook for vendor-specific campsite parent resolution.
 *
 * The registry section is still named `poi_reservable_joiner` for YAML
 * compatibility, but the removed `reservables` / `reservable_pois` tables are
 * not part of this contract anymore. Canonicalized adapters discover
 * `(campsite_id, campground_id)` pairs using `campsites`, `campgrounds`, and
 * `vendor_refs`; the orchestrator keeps this phase disabled until the
 * canonical catalog writer/reconciler lands.
 */
interface PoiReservableJoiner {
    /** Adapter identifier; matches the YAML `adapter:` field. */
    val adapter: String

    /** Find canonical campsite → campground parent pairs for this vendor. */
    fun discoverLinks(ctx: JoinerCtx): List<Link>

    /** Delete stale links in this adapter's provider scope when re-enabled. */
    fun sweepStaleLinks(ctx: JoinerCtx): Int = 0

    data class Link(
        val campsiteId: Long,
        val campgroundId: Long,
    )
}

data class JoinerCtx(
    val ctx: DSLContext,
    /** YAML `args:` map for the entry; empty when not declared. */
    val args: Map<String, String> = emptyMap(),
)
