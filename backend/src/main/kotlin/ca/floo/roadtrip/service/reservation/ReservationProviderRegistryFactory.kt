package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.models.metadata.registry.RegistryCapabilityLimit
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.aspira.AspiraTenants
import ca.floo.roadtrip.service.reservation.adapters.recgov.RecGovReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaTenant
import ca.floo.roadtrip.service.reservation.adapters.reservecalifornia.ReserveCaliforniaReservationProvider

/**
 * Builds a [ReservationProviderRegistry] from boot-time config + clients. One
 * place that knows the mapping from `pois.source` to [ReservationProvider];
 * keeps that knowledge out of [Main] (which would otherwise have to wire
 * each adapter manually) and out of routes.
 *
 * Aspira hosts come from the YAML registry — see [PoiRegistry.aspiraHostBySource]
 * — and resolve to a tenant config row in [AspiraTenants]. ReserveAmerica
 * tenant host/contract/horizon config comes directly from the YAML row.
 */
object ReservationProviderRegistryFactory {
    fun build(
        registry: PoiRegistry,
        clients: ReservationProviderClients,
    ): ReservationProviderRegistry {
        val adaptersBySource = mutableMapOf<String, ReservationProvider>()

        // RecGov — single adapter instance shared across every recgov source.
        val recgov = RecGovReservationProvider(client = clients.recgovClient)
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
                        client = clients.aspiraClient,
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
                    bookingHorizon = config.bookingHorizon.toCapabilityLimit(),
                )
            adaptersBySource[config.source] =
                ReserveAmericaReservationProvider(
                    tenant = tenant,
                    client = clients.reserveAmericaClient,
                )
        }

        val reserveCaliforniaSources = registry.reserveCaliforniaSources()
        if (reserveCaliforniaSources.isNotEmpty()) {
            val reserveCalifornia = ReserveCaliforniaReservationProvider(client = clients.reserveCaliforniaClient)
            for (source in reserveCaliforniaSources) {
                adaptersBySource[source] = reserveCalifornia
            }
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

private fun RegistryCapabilityLimit.toCapabilityLimit(): CapabilityLimit =
    CapabilityLimit(
        value = value,
        unit = unit,
    )
