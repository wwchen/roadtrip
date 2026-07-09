package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRefRow

/**
 * Holds the live reservation-provider adapters and dispatches a selected
 * `(vendor_ref.vendor, provider_ref)` pair to the right one.
 *
 * Construction is the only place that knows the mapping from source/vendor
 * keys to a [ReservationProvider] instance. Once built, the registry exposes a
 * single lookup — routes and the watch poller never see the source string, and
 * adapters never see the source either.
 *
 * Key shape note: a single [ReservationProviderId] value can map to multiple
 * adapter *instances* (Aspira NextGen runs three tenants — PC/BC/WA — that
 * share a wire shape but have different hosts, caches, and reservable
 * vendor codes). The registry is keyed by `pois.source`, not by id, so
 * each tenant's source resolves to its own adapter while the public
 * provider id stays vendor-shaped.
 *
 * Held as a singleton in [Main]; safe to share across coroutines.
 */
class ReservationProviderRegistry(
    /**
     * Source/vendor key → adapter instance. Most keys are terminal ETL slugs
     * from YAML; canonical catalog reads can also surface `vendor_refs.vendor`
     * values such as `campflare`.
     */
    private val adaptersBySource: Map<String, ReservationProvider>,
) {
    /**
     * Look up the adapter that handles a campground POI row. Returns null
     * when the source is unmapped (e.g. a brand-new ReserveAmerica tenant,
     * or a brand-new ETL whose registry entry forgot to set a provider).
     */
    fun forPoi(row: CampsiteProviderRefRow): ReservationProvider? = adaptersBySource[row.source]

    fun forPoi(
        row: CampsiteProviderRefRow,
        ref: ProviderRef,
    ): ReservationProvider? =
        adaptersBySource[row.source]
            ?.takeIf { it.id == ref.reservationProviderId() }

    /**
     * Source-only lookup for call sites that only need static adapter
     * capabilities. Use [forPoi] when future provider routing may need the
     * full campground row.
     */
    fun forSource(source: String): ReservationProvider? = adaptersBySource[source]

    /**
     * All distinct adapter instances. Used by capability probes and admin
     * tooling. The same instance may appear under multiple sources
     * (e.g. one RecGov adapter handles every recgov source); this method
     * returns it once.
     */
    fun all(): Collection<ReservationProvider> = adaptersBySource.values.toSet()

    /**
     * First adapter found with the given vendor id. Convenience for tests
     * and capability endpoints that don't care which tenant they hit.
     * Returns null if no adapter for that vendor is registered.
     */
    fun firstByVendor(id: ReservationProviderId): ReservationProvider? = adaptersBySource.values.firstOrNull { it.id == id }
}
