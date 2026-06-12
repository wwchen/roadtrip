package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.repo.ReservableRepo
import org.jooq.DSLContext

/**
 * Discovers (reservable, poi) pairs that should be linked. RFC 0008.
 *
 * Joiners read the current state of both tables (`reservables` + `pois`)
 * and emit link records that the orchestrator upserts via
 * [ReservableRepo.linkToPois] (idempotent — re-running on existing links
 * is a no-op). Adapters can also sweep stale links in their provider scope.
 *
 * Each adapter knows ONE provider's keying scheme. RecGov knows that
 * `pois.source = federal-campgrounds` and `reservables.raw->>'campsite_id'`
 * shares a parent facility id. Aspira knows that
 * `pois.source = aspira-{tenant}-pins` with
 * `pois.source_id = "aspira-{txn}-{map}"` matches `reservables.raw`'s
 * upstream identifiers. Joiners don't share knowledge across providers.
 *
 * Joiner runs are independent of ETL runs. After both `pois` and
 * `reservables` are populated for a vendor, run the matching joiner.
 * Re-running creates no duplicate links; it picks up new pairs and removes
 * links whose source-side rows no longer match.
 *
 * The interface is intentionally minimal: no parse/validate/transform
 * stages, no inputs map. The adapter's `discoverLinks` is one DB query
 * (or several) that returns the pairs to write.
 */
interface PoiReservableJoiner {
    /** Adapter identifier; matches the YAML `adapter:` field. */
    val adapter: String

    /**
     * Find pairs of (reservable_id, poi_id) that should be linked.
     * Reads from [ctx]; idempotency is the orchestrator's concern via
     * [ReservableRepo.linkToPois]'s ON CONFLICT DO NOTHING.
     */
    fun discoverLinks(ctx: JoinerCtx): List<Link>

    /** Delete stale links in this adapter's provider scope. */
    fun sweepStaleLinks(ctx: JoinerCtx): Int = 0

    /** A discovered link. Surrogate ids on both sides. */
    data class Link(
        val reservableId: Long,
        val poiId: Long,
    )
}

/**
 * Context handed to [PoiReservableJoiner.discoverLinks]. Exposes
 * [ReservableRepo] for catalog reads + a raw [DSLContext] for adapters
 * that want to issue joiner-specific SQL (the recgov joiner does this
 * — it matches by parsing JSONB in `reservables.raw`). POI reads
 * happen via [DSLContext]; there's no `PoiRepo` class today, just
 * jOOQ-based access patterns the joiner adapter writes inline.
 */
data class JoinerCtx(
    val ctx: DSLContext,
    val reservables: ReservableRepo,
    /** YAML `args:` map for the entry; empty when not declared. */
    val args: Map<String, String> = emptyMap(),
)
