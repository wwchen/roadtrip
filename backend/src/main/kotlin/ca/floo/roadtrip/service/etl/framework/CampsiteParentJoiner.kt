package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampsiteParentLink

/**
 * Post-import parent reconciler for vendor-specific campsite → campground
 * relationships.
 *
 * Canonical campsite ETLs emit `parentVendor` + `parentVendorRefId` and
 * CampsiteRepo.upsertCampsites resolves them through `vendor_refs` to set
 * `campsites.campground_id`. That's the source of truth for the parent link
 * at write time.
 *
 * A joiner is a second, cross-vendor pass over the same schema. The repo owns
 * the SQL that fetches narrow campsite/campground candidate projections, and
 * each adapter owns the vendor-specific matching policy that turns those
 * candidates into parent links. When a discovered pair disagrees with the
 * current `campsites.campground_id`, `EtlOrchestrator.runJoiner` reparents the
 * campsite through the same repo boundary. Idempotent on already-correct rows;
 * useful when vendor payloads shift over time or when a source cannot reliably
 * resolve every parent through its import-time row.
 */
interface CampsiteParentJoiner {
    /** Adapter identifier; matches the YAML `adapter:` field. */
    val adapter: String

    /** Find canonical campsite → campground parent pairs for this vendor. */
    fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink>

    /**
     * Optional adapter hook to prune vendor-scoped rows the discoverLinks
     * pass no longer emits. Defaults to zero; adapters override when they
     * own a scope (e.g. one-provider-vs-all-links) that supports pruning.
     */
    fun sweepStaleLinks(ctx: JoinerCtx): Int = 0
}
