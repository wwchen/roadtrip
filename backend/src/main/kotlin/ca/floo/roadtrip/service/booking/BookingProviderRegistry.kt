package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.repo.CampsiteProviderRefRow

/**
 * Holds the live booking-provider adapters and dispatches a
 * `(pois.source, provider_ref)` pair to the right one.
 *
 * Construction is the only place that knows the mapping from `pois.source`
 * (an ETL slug) to a [BookingProvider] instance. Once built, the registry
 * exposes a single lookup — routes and the alert poller never see the
 * source string, and adapters never see the source either.
 *
 * Key shape note: a single [BookingProviderId] value can map to multiple
 * adapter *instances* (Aspira NextGen runs three tenants — PC/BC/WA — that
 * share a wire shape but have different hosts, caches, and reservable
 * vendor codes). The registry is keyed by `pois.source`, not by id, so
 * each tenant's source resolves to its own adapter while the public
 * provider id stays vendor-shaped.
 *
 * Held as a singleton in [Main]; safe to share across coroutines.
 */
class BookingProviderRegistry(
    /**
     * `pois.source` → adapter instance. One row per terminal-ETL slug
     * that produces bookable POIs. Source strings come from the YAML
     * registry; adapters are built by [BookingProviderRegistryFactory].
     */
    private val adaptersBySource: Map<String, BookingProvider>,
) {
    /**
     * Look up the adapter that handles a campground POI row. Returns null
     * when the source is unmapped (e.g. Camis before its adapter is wired,
     * or a brand-new ETL whose registry entry forgot to set a provider).
     */
    fun forPoi(row: CampsiteProviderRefRow): BookingProvider? = adaptersBySource[row.source]

    /**
     * All distinct adapter instances. Used by capability probes and admin
     * tooling. The same instance may appear under multiple sources
     * (e.g. one RecGov adapter handles every recgov source); this method
     * returns it once.
     */
    fun all(): Collection<BookingProvider> = adaptersBySource.values.toSet()

    /**
     * First adapter found with the given vendor id. Convenience for tests
     * and capability endpoints that don't care which tenant they hit.
     * Returns null if no adapter for that vendor is registered.
     */
    fun firstByVendor(id: BookingProviderId): BookingProvider? = adaptersBySource.values.firstOrNull { it.id == id }
}
