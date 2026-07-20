package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry

/**
 * Holds the live availability-provider adapters and dispatches a selected
 * `(booking_provider, booking_provider_ref)` pair to the right one.
 *
 * Construction is the only place that knows the mapping from source/booking
 * keys to a [AvailabilityProvider] instance. Once built, the registry exposes a
 * single lookup — routes and the watch poller never see the source string, and
 * adapters never see the source either.
 *
 * Key shape note: a single [BookingProvider] value can map to multiple
 * adapter *instances* (Aspira NextGen runs three tenants — PC/BC/WA — that
 * share a wire shape but have different hosts, caches, and campsite
 * booking codes). The registry is keyed by the catalog source slug (booking_provider tenant key), not by id, so
 * each tenant's source resolves to its own adapter while the public
 * provider id stays vendor-shaped.
 *
 * Held as a singleton in [Main]; safe to share across coroutines.
 */
class AvailabilityProviderRegistry(
    /**
     * Source/booking key → adapter instance. Most keys are terminal ETL slugs
     * from YAML; canonical catalog reads can also surface `booking_provider`
     * values such as `campflare`.
     */
    private val adaptersBySource: Map<String, AvailabilityProvider>,
) {
    /**
     * Typed lookup: resolve adapter from a [BookingProvider] enum + parsed [BookingProviderRef].
     * For multi-tenant providers (Aspira, ReserveAmerica), extracts the
     * tenant from the ref to select the correct adapter instance.
     */
    fun forBooking(
        provider: BookingProvider,
        ref: BookingProviderRef,
    ): AvailabilityProvider? {
        val key =
            when (ref) {
                is BookingProviderRef.Aspira -> "aspira_${ref.tenant}"
                is BookingProviderRef.ReserveAmerica ->
                    adaptersBySource.keys.firstOrNull { source ->
                        val adapter = adaptersBySource[source]
                        adapter?.id == BookingProvider.RESERVEAMERICA &&
                            (adapter as? ReserveAmericaAvailabilityProvider)?.tenant?.contractCode == ref.contractCode
                    }
                is BookingProviderRef.RecGov -> RECGOV_VENDOR
                is BookingProviderRef.Campflare -> CAMPFLARE_VENDOR
                is BookingProviderRef.ReserveCalifornia ->
                    adaptersBySource.keys.firstOrNull { source ->
                        adaptersBySource[source]?.id == BookingProvider.RESERVECALIFORNIA
                    }
            } ?: return null
        return adaptersBySource[key]?.takeIf { it.isEnabled() && it.supportsRef(ref) }
    }

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
    fun firstByVendor(id: BookingProvider): AvailabilityProvider? = adaptersBySource.values.firstOrNull { it.id == id && it.isEnabled() }

    companion object {
        private const val CAMPFLARE_VENDOR = "campflare"
        private const val RECGOV_VENDOR = "recgov"
        private val aspiraBookingProviderBySource =
            mapOf(
                "aspira-wa-campgrounds" to "aspira_wa",
                "aspira-bc-campgrounds" to "aspira_bc",
                "aspira-pc-campgrounds" to "aspira_pc",
            )

        fun fromPoiRegistry(
            registry: PoiRegistry,
            clients: AvailabilityProviderClients,
            isProviderEnabled: (BookingProvider) -> Boolean,
        ): AvailabilityProviderRegistry {
            val adaptersBySource = mutableMapOf<String, AvailabilityProvider>()

            // RecGov — single adapter instance shared across every recgov source.
            val recgov =
                RecGovAvailabilityProvider(
                    availabilityClient = clients.recgovClient,
                    enabled = isProviderEnabled(BookingProvider.RECGOV),
                )
            adaptersBySource[RECGOV_VENDOR] = recgov
            for (source in registry.recgovSources()) {
                adaptersBySource[source] = recgov
            }

            // Canonical catalog reads expose `booking_provider` ("campflare"),
            // while registry YAML exposes the terminal ETL slug ("campflare-campgrounds").
            val campflare =
                CampflareAvailabilityProvider(
                    availabilityClient = clients.campflareClient,
                    enabled = isProviderEnabled(BookingProvider.CAMPFLARE),
                )
            adaptersBySource[CAMPFLARE_VENDOR] = campflare
            for (source in registry.campflareSources()) {
                adaptersBySource[source] = campflare
            }

            // Aspira — one adapter instance per upstream host. Sources that share
            // a host share an instance. Registered under both the YAML ETL slug
            // (e.g. "aspira-wa-campgrounds") and the unified DataProvider id
            // (e.g. "aspira_wa") so lookups from campgrounds.data_provider resolve.
            val hostBySource = registry.hostBySource().filter { (_, host) -> AspiraTenants.byHost(host) != null }
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
                            availabilityClient = clients.aspiraClient,
                            enabled = isProviderEnabled(BookingProvider.ASPIRA),
                        )
                    }
                adaptersBySource[source] = adapter
                val bookingProviderCode = aspiraBookingProviderBySource[source]
                if (bookingProviderCode != null) adaptersBySource[bookingProviderCode] = adapter
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
                        availabilityClient = clients.reserveAmericaClient,
                        enabled = isProviderEnabled(BookingProvider.RESERVEAMERICA),
                    )
            }

            val reserveCaliforniaSources = registry.reserveCaliforniaSources()
            if (reserveCaliforniaSources.isNotEmpty()) {
                val reserveCalifornia =
                    ReserveCaliforniaAvailabilityProvider(
                        availabilityClient = clients.reserveCaliforniaClient,
                        enabled = isProviderEnabled(BookingProvider.RESERVECALIFORNIA),
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
    }
}
