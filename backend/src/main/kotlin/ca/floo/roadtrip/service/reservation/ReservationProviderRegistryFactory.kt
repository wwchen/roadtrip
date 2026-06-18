package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.cache.CachedAspiraAvailability
import ca.floo.roadtrip.clients.cache.CachedRecGovAvailability
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraTenants
import ca.floo.roadtrip.service.reservation.adapters.camis.CamisReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.recgov.RecGovReservationProvider

/**
 * Builds a [ReservationProviderRegistry] from boot-time config + caches. One
 * place that knows the mapping from `pois.source` to [ReservationProvider];
 * keeps that knowledge out of [Main] (which would otherwise have to wire
 * each adapter manually) and out of routes.
 *
 * Aspira hosts come from the YAML registry — see
 * [PoiRegistry.aspiraHostBySource] — and resolve to a tenant config row
 * in [AspiraTenants]. Each unique host gets one adapter instance.
 * Adding a tenant is one row in [AspiraTenants] plus a YAML row; no
 * change here.
 */
object ReservationProviderRegistryFactory {
    fun build(
        registry: PoiRegistry,
        recgovCache: CachedRecGovAvailability,
        aspiraCache: CachedAspiraAvailability,
    ): ReservationProviderRegistry {
        val adaptersBySource = mutableMapOf<String, ReservationProvider>()

        // RecGov — single adapter instance shared across every recgov source.
        val recgov = RecGovReservationProvider(cache = recgovCache)
        for (source in registry.recgovSources()) {
            adaptersBySource[source] = recgov
        }

        // Aspira — one adapter instance per upstream host. Sources that share
        // a host share an instance.
        val hostBySource = registry.aspiraHostBySource()
        validateAspiraHosts(hostBySource)
        val aspiraByHost = mutableMapOf<String, AspiraReservationProvider>()
        for ((source, host) in hostBySource) {
            val adapter =
                aspiraByHost.getOrPut(host) {
                    val tenant =
                        AspiraTenants.byHost(host)
                            ?: error(
                                "Aspira host '$host' has no AspiraTenant config row; " +
                                    "add it to AspiraTenants.kt.",
                            )
                    AspiraReservationProvider(
                        tenant = tenant,
                        cache = aspiraCache,
                    )
                }
            adaptersBySource[source] = adapter
        }

        // Camis — capability stub. Wired so registry dispatch is exhaustive
        // and so the adapter matrix is honest about what we don't yet
        // support; calls throw ReservationProviderError.Unsupported.
        val camis = CamisReservationProvider()
        for (source in registry.camisSources()) {
            adaptersBySource[source] = camis
        }

        return ReservationProviderRegistry(adaptersBySource = adaptersBySource.toMap())
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
