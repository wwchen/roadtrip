package ca.floo.roadtrip.service.etl.framework

import org.jooq.DSLContext

/**
 * Post-import parent reconciler for vendor-specific campsite → campground
 * relationships.
 *
 * Canonical campsite ETLs emit `parentVendor` + `parentVendorRefId` and
 * CanonicalCatalogRepo.upsertCampsite resolves them through `vendor_refs`
 * to set `campsites.campground_id`. That's the source of truth for the
 * parent link at write time.
 *
 * A joiner is a second, cross-vendor pass over the same schema — with each
 * adapter carrying vendor-specific SQL predicates that recover the "correct"
 * parent from vendor payloads. When a joiner's discovered pair disagrees
 * with the current `campsites.campground_id`, `EtlOrchestrator.runJoiner`
 * reparents the campsite. Idempotent on already-correct rows; useful when
 * vendor payloads shift over time (Aspira leaf reassignments, rec.gov
 * facility moves) or when a future cross-vendor merge exposes a better
 * parent than the source-of-truth ETL saw at write time.
 */
interface PoiReservableJoiner {
    /** Adapter identifier; matches the YAML `adapter:` field. */
    val adapter: String

    /** Find canonical campsite → campground parent pairs for this vendor. */
    fun discoverLinks(ctx: JoinerCtx): List<Link>

    /**
     * Optional adapter hook to prune vendor-scoped rows the discoverLinks
     * pass no longer emits. Defaults to zero; adapters override when they
     * own a scope (e.g. one-provider-vs-all-links) that supports pruning.
     */
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
