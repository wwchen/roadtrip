package ca.floo.roadtrip.service.booking

import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.booking.adapters.aspira.AspiraBookingProvider
import ca.floo.roadtrip.service.booking.adapters.aspira.AspiraTenants
import ca.floo.roadtrip.service.booking.adapters.camis.CamisBookingProvider
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovBookingProvider

/**
 * Builds a [BookingProviderRegistry] from boot-time config + caches. One
 * place that knows the mapping from `pois.source` to [BookingProvider];
 * keeps that knowledge out of [Main] (which would otherwise have to wire
 * each adapter manually) and out of routes.
 *
 * Aspira hosts come from the YAML registry — see
 * [PoiRegistry.aspiraHostBySource] — and resolve to a tenant config row
 * in [AspiraTenants]. Each unique host gets one adapter instance.
 * Adding a tenant is one row in [AspiraTenants] plus a YAML row; no
 * change here.
 */
object BookingProviderRegistryFactory {
    fun build(
        registry: PoiRegistry,
        recgovCache: CachedAvailability,
        aspiraCache: CachedAspiraAvailability,
    ): BookingProviderRegistry {
        val adaptersBySource = mutableMapOf<String, BookingProvider>()

        // RecGov — single adapter instance shared across every recgov source.
        val recgov = RecGovBookingProvider(cache = recgovCache)
        for (source in registry.recgovSources()) {
            adaptersBySource[source] = recgov
        }

        // Aspira — one adapter instance per upstream host. Sources that share
        // a host share an instance.
        val hostBySource = registry.aspiraHostBySource()
        validateAspiraHosts(hostBySource)
        val aspiraByHost = mutableMapOf<String, AspiraBookingProvider>()
        for ((source, host) in hostBySource) {
            val adapter =
                aspiraByHost.getOrPut(host) {
                    val tenant =
                        AspiraTenants.byHost(host)
                            ?: error(
                                "Aspira host '$host' has no AspiraTenant config row; " +
                                    "add it to AspiraTenants.kt.",
                            )
                    AspiraBookingProvider(
                        tenant = tenant,
                        cache = aspiraCache,
                    )
                }
            adaptersBySource[source] = adapter
        }

        // Camis — capability stub. Wired so registry dispatch is exhaustive
        // and so the adapter matrix is honest about what we don't yet
        // support; calls throw BookingProviderError.Unsupported.
        val camis = CamisBookingProvider()
        for (source in registry.camisSources()) {
            adaptersBySource[source] = camis
        }

        return BookingProviderRegistry(adaptersBySource = adaptersBySource.toMap())
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
                "Aspira hosts declared in poi-registry.yaml but missing from AspiraTenants: " +
                    "$missingFromConfig. Add a tenant row in AspiraTenants.kt.",
            )
        }
        // Reverse direction is informational, not fatal: a tenant row with no
        // YAML source is harmless (the adapter just won't be exercised).
    }
}
