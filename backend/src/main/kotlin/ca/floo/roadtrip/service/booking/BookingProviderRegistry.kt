package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.repo.CampsiteProviderRefRow

/**
 * Holds the live booking-provider adapters and dispatches a
 * `(pois.source, provider_ref)` pair to the right one.
 *
 * Construction is the only place that knows the mapping from `pois.source`
 * (an ETL slug) to [BookingProviderId]. Once built, the registry exposes a
 * single lookup — routes and the alert poller never see the source string,
 * and adapters never see the source either.
 *
 * Held as a singleton in [Main]; safe to share across coroutines.
 */
class BookingProviderRegistry(
    private val adapters: Map<BookingProviderId, BookingProvider>,
    /**
     * Mapping from terminal-ETL slug (`pois.source`) to provider id. Built at
     * boot from the YAML registry — see [PoiRegistry.aspiraHostBySource] for
     * the analogous mapping it replaces. A row whose source isn't in this
     * map has no adapter and `forPoi` returns null.
     */
    private val sourceToProviderId: Map<String, BookingProviderId>,
) {
    /**
     * Look up the adapter that handles a campground POI row. Returns null
     * when the source is unmapped (e.g. Camis before its adapter is wired,
     * or a brand-new ETL whose registry entry forgot to set a provider id).
     */
    fun forPoi(row: CampsiteProviderRefRow): BookingProvider? {
        val id = sourceToProviderId[row.source] ?: return null
        return adapters[id]
    }

    /** All known providers — used by capability probes and admin tooling. */
    fun all(): Collection<BookingProvider> = adapters.values

    /** Direct lookup by id — used by tests and capability endpoints. */
    fun get(id: BookingProviderId): BookingProvider? = adapters[id]
}
