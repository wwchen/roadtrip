package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.model.domain.ProviderRef
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry

/**
 * Holds the live availability-provider adapters and dispatches a selected
 * `(vendor_ref.vendor, provider_ref)` pair to the right one.
 *
 * Construction is the only place that knows the mapping from source/vendor
 * keys to a [AvailabilityProvider] instance. Once built, the registry exposes a
 * single lookup — routes and the watch poller never see the source string, and
 * adapters never see the source either.
 *
 * Key shape note: a single [AvailabilityProviderId] value can map to multiple
 * adapter *instances* (Aspira NextGen runs three tenants — PC/BC/WA — that
 * share a wire shape but have different hosts, caches, and campsite
 * vendor codes). The registry is keyed by the catalog source slug (`vendor_refs.vendor` tenant key), not by id, so
 * each tenant's source resolves to its own adapter while the public
 * provider id stays vendor-shaped.
 *
 * Held as a singleton in [Main]; safe to share across coroutines.
 */
class AvailabilityProviderRegistry(
    /**
     * Source/vendor key → adapter instance. Most keys are terminal ETL slugs
     * from YAML; canonical catalog reads can also surface `vendor_refs.vendor`
     * values such as `campflare`.
     */
    private val adaptersBySource: Map<String, AvailabilityProvider>,
) {
    /**
     * Look up the adapter that handles a campground POI row. Returns null
     * when the source is unmapped (e.g. a brand-new ReserveAmerica tenant,
     * or a brand-new ETL whose registry entry forgot to set a provider).
     */
    fun forPoi(row: CampsiteProviderRefRow): AvailabilityProvider? =
        adaptersBySource[row.source]
            ?.takeIf { it.isEnabled() }

    fun forPoi(
        row: CampsiteProviderRefRow,
        ref: ProviderRef,
    ): AvailabilityProvider? =
        adaptersBySource[row.source]
            ?.takeIf { it.isEnabled() && it.supportsRef(ref) }

    /**
     * Source-only lookup for call sites that only need static adapter
     * capabilities. Use [forPoi] when future provider routing may need the
     * full campground row.
     */
    fun forSource(source: String): AvailabilityProvider? =
        adaptersBySource[source]
            ?.takeIf { it.isEnabled() }

    /**
     * All distinct adapter instances. Used by capability probes and admin
     * tooling. The same instance may appear under multiple sources
     * (e.g. one RecGov adapter handles every recgov source); this method
     * returns it once.
     */
    fun all(): Collection<AvailabilityProvider> = adaptersBySource.values.filter { it.isEnabled() }.toSet()

    /**
     * First adapter found with the given vendor id. Convenience for tests
     * and capability endpoints that don't care which tenant they hit.
     * Returns null if no adapter for that vendor is registered.
     */
    fun firstByVendor(id: AvailabilityProviderId): AvailabilityProvider? =
        adaptersBySource.values.firstOrNull { it.id == id && it.isEnabled() }

    companion object {
        fun fromPoiRegistry(
            registry: PoiRegistry,
            clients: AvailabilityProviderClients,
            isProviderEnabled: (AvailabilityProviderId) -> Boolean,
        ): AvailabilityProviderRegistry {
            val adaptersBySource = mutableMapOf<String, AvailabilityProvider>()

            // RecGov — single adapter instance shared across every recgov source.
            val recgov =
                RecGovAvailabilityProvider(
                    client = clients.recgovClient,
                    enabled = isProviderEnabled(AvailabilityProviderId.RECGOV),
                )
            adaptersBySource[RECGOV_VENDOR] = recgov
            for (source in registry.recgovSources()) {
                adaptersBySource[source] = recgov
            }

            // Canonical catalog reads expose `vendor_refs.vendor` ("campflare"),
            // while registry YAML exposes the terminal ETL slug ("campflare-campgrounds").
            val campflare =
                CampflareAvailabilityProvider(
                    client = clients.campflareClient,
                    enabled = isProviderEnabled(AvailabilityProviderId.CAMPFLARE),
                )
            adaptersBySource[CAMPFLARE_VENDOR] = campflare
            for (source in registry.campflareSources()) {
                adaptersBySource[source] = campflare
            }

            // Aspira — one adapter instance per upstream host. Sources that share
            // a host share an instance.
            val hostBySource = registry.aspiraHostBySource()
            validateAspiraHosts(hostBySource)
            val aspiraByHost = mutableMapOf<String, AspiraAvailabilityProvider>()
            for ((source, host) in hostBySource) {
                val adapter =
                    aspiraByHost.getOrPut(host) {
                        val tenant =
                            AspiraTenants.byHost(host)
                                ?: error(
                                    "Aspira host '$host' has no AspiraTenant config row; " +
                                        "add it to AspiraTenants.kt.",
                                )
                        AspiraAvailabilityProvider(
                            tenant = tenant,
                            client = clients.aspiraClient,
                            enabled = isProviderEnabled(AvailabilityProviderId.ASPIRA),
                        )
                    }
                adaptersBySource[source] = adapter
            }

            // ReserveAmerica / Active Network — one adapter per tenant source.
            for (config in registry.reserveAmericaSources()) {
                val tenant =
                    ReserveAmericaTenant(
                        source = config.source,
                        host = config.host,
                        contractCode = config.contractCode,
                        bookingHorizonDays = config.bookingHorizonDays,
                    )
                adaptersBySource[config.source] =
                    ReserveAmericaAvailabilityProvider(
                        tenant = tenant,
                        client = clients.reserveAmericaClient,
                        enabled = isProviderEnabled(AvailabilityProviderId.RESERVEAMERICA),
                    )
            }

            val reserveCaliforniaSources = registry.reserveCaliforniaSources()
            if (reserveCaliforniaSources.isNotEmpty()) {
                val reserveCalifornia =
                    ReserveCaliforniaAvailabilityProvider(
                        client = clients.reserveCaliforniaClient,
                        enabled = isProviderEnabled(AvailabilityProviderId.RESERVECALIFORNIA),
                    )
                for (source in reserveCaliforniaSources) {
                    adaptersBySource[source] = reserveCalifornia
                }
            }

            return AvailabilityProviderRegistry(adaptersBySource = adaptersBySource.toMap())
        }

        /**
         * Boot-time gate: every Aspira host the YAML declares must have a
         * tenant config row, and vice versa. Catches forgotten entries
         * loudly instead of letting a request silently route to a missing
         * adapter at the first user click.
         */
        private fun validateAspiraHosts(hostBySource: Map<String, String>) {
            val yamlHosts = hostBySource.values.toSet()
            val configHosts = AspiraTenants.knownHosts()
            val missingFromConfig = yamlHosts - configHosts
            if (missingFromConfig.isNotEmpty()) {
                error(
                    "Aspira hosts declared in POI registry but missing from AspiraTenants: " +
                        "$missingFromConfig. Add a tenant row in AspiraTenants.kt.",
                )
            }
            // Reverse direction is informational, not fatal: a tenant row with no
            // YAML source is harmless (the adapter just won't be exercised).
        }

        private const val CAMPFLARE_VENDOR = "campflare"
        private const val RECGOV_VENDOR = "recgov"
    }
}
