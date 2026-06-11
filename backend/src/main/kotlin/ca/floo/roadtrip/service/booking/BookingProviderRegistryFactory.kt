package ca.floo.roadtrip.service.booking

import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.booking.adapters.aspira.AspiraBookingProvider
import ca.floo.roadtrip.service.booking.adapters.camis.CamisBookingProvider
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovBookingProvider

/**
 * Builds a [BookingProviderRegistry] from boot-time config + caches. One
 * place that knows the mapping from `pois.source` to [BookingProviderId];
 * keeps that knowledge out of [Main] (which would otherwise have to wire
 * each adapter manually) and out of routes.
 *
 * Aspira hosts come from the YAML registry — see
 * [PoiRegistry.aspiraHostBySource]. Each unique host gets one adapter
 * instance with that host bound, identified by [BookingProviderId].
 */
object BookingProviderRegistryFactory {
    /**
     * Maps Aspira host → typed provider id. The set is closed and known at
     * compile time; new hosts require a new enum value (a deliberate
     * decision rather than a silent autogen).
     */
    private val ASPIRA_HOST_TO_ID: Map<String, BookingProviderId> =
        mapOf(
            "reservation.pc.gc.ca" to BookingProviderId.ASPIRA_PC,
            "camping.bcparks.ca" to BookingProviderId.ASPIRA_BC,
            "washington.goingtocamp.com" to BookingProviderId.ASPIRA_WA,
        )

    fun build(
        registry: PoiRegistry,
        recgovCache: CachedAvailability,
        aspiraCache: CachedAspiraAvailability,
    ): BookingProviderRegistry {
        val adapters = mutableMapOf<BookingProviderId, BookingProvider>()
        val sourceToProviderId = mutableMapOf<String, BookingProviderId>()

        // RecGov — single instance, one or more sources point at it.
        val recgov = RecGovBookingProvider(cache = recgovCache)
        adapters[recgov.id] = recgov
        for (source in registry.recgovSources()) {
            sourceToProviderId[source] = recgov.id
        }

        // Aspira — one adapter instance per upstream host. Each source maps
        // to whichever host its terminal ETL declared in YAML.
        val hostBySource = registry.aspiraHostBySource()
        val uniqueHosts = hostBySource.values.toSet()
        for (host in uniqueHosts) {
            val providerId =
                ASPIRA_HOST_TO_ID[host]
                    ?: error("Aspira host $host has no BookingProviderId mapping; add an enum value.")
            val adapter = AspiraBookingProvider(id = providerId, host = host, cache = aspiraCache)
            adapters[providerId] = adapter
        }
        for ((source, host) in hostBySource) {
            val providerId =
                ASPIRA_HOST_TO_ID[host]
                    ?: error("Aspira host $host has no BookingProviderId mapping; add an enum value.")
            sourceToProviderId[source] = providerId
        }

        // Camis — capability stub. Wired so registry dispatch is exhaustive
        // and so the adapter matrix is honest about what we don't yet
        // support; calls throw BookingProviderError.Unsupported.
        val camis = CamisBookingProvider()
        adapters[camis.id] = camis
        for (source in registry.camisSources()) {
            sourceToProviderId[source] = camis.id
        }

        return BookingProviderRegistry(
            adapters = adapters.toMap(),
            sourceToProviderId = sourceToProviderId.toMap(),
        )
    }
}
