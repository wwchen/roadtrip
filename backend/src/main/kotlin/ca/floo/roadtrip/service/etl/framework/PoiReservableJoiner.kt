package ca.floo.roadtrip.service.etl.framework

import org.jooq.DSLContext

/**
 * Retired legacy import hook for vendor-specific campsite parent resolution.
 *
 * The registry section is still named `poi_reservable_joiner` for YAML
 * compatibility, but the removed `reservables` / `reservable_pois` tables are
 * not part of this contract anymore. Canonical campsite ETLs emit
 * `parentVendor` and `parentVendorRefId`; CanonicalCatalogRepo resolves those
 * against `vendor_refs` during upsert, so the orchestrator keeps this phase
 * out of import fan-out.
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
