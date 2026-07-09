package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraTenants
import ca.floo.roadtrip.service.availability.provider.adapters.campflare.CampflareAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaTenant
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaAvailabilityProvider

/**
 * Builds a [AvailabilityProviderRegistry] from boot-time config + clients. One
 * place that knows the mapping from `pois.source` to [AvailabilityProvider];
 * keeps that knowledge out of [Main] (which would otherwise have to wire
 * each adapter manually) and out of routes.
 *
 * Aspira hosts come from the YAML registry — see [PoiRegistry.aspiraHostBySource]
 * — and resolve to a tenant config row in [AspiraTenants]. ReserveAmerica
 * tenant host/contract/horizon config comes directly from the YAML row.
 */
object AvailabilityProviderRegistryFactory {
    fun build(
        registry: PoiRegistry,
        clients: AvailabilityProviderClients,
        campflareApiKeyConfigured: Boolean = true,
    ): AvailabilityProviderRegistry {
        val adaptersBySource = mutableMapOf<String, AvailabilityProvider>()

        // RecGov — single adapter instance shared across every recgov source.
        val recgov = RecGovAvailabilityProvider(client = clients.recgovClient)
        for (source in registry.recgovSources()) {
            adaptersBySource[source] = recgov
        }

        // Canonical catalog reads expose `vendor_refs.vendor` ("campflare"),
        // while registry YAML exposes the terminal ETL slug ("campflare-campgrounds").
        val campflare =
            CampflareAvailabilityProvider(
                client = clients.campflareClient,
                apiKeyConfigured = campflareApiKeyConfigured,
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
                )
        }

        val reserveCaliforniaSources = registry.reserveCaliforniaSources()
        if (reserveCaliforniaSources.isNotEmpty()) {
            val reserveCalifornia = ReserveCaliforniaAvailabilityProvider(client = clients.reserveCaliforniaClient)
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
                "Aspira hosts declared in poi-registry.yaml but missing from AspiraTenants: " +
                    "$missingFromConfig. Add a tenant row in AspiraTenants.kt.",
            )
        }
        // Reverse direction is informational, not fatal: a tenant row with no
        // YAML source is harmless (the adapter just won't be exercised).
    }

    private const val CAMPFLARE_VENDOR = "campflare"
}
