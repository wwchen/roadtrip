package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.models.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaAvailabilityProvider

private const val CAMPFLARE_VENDOR = "campflare"
private const val RECGOV_VENDOR = "recgov"

internal fun availabilityProvidersBySource(
    poiRegistry: PoiRegistry,
    providers: List<AvailabilityProvider>,
): Map<String, AvailabilityProvider> {
    val pairs =
        buildList {
            val recgovSources = poiRegistry.recgovSources()
            if (recgovSources.isNotEmpty()) {
                addSourceBindings(
                    provider = providers.singleAvailabilityProviderById(AvailabilityProviderId.RECGOV),
                    sources = listOf(RECGOV_VENDOR) + recgovSources,
                )
            }

            val campflareSources = poiRegistry.campflareSources()
            if (campflareSources.isNotEmpty()) {
                addSourceBindings(
                    provider = providers.singleAvailabilityProviderById(AvailabilityProviderId.CAMPFLARE),
                    sources = listOf(CAMPFLARE_VENDOR) + campflareSources,
                )
            }

            val aspiraByHost = providers.filterIsInstance<AspiraAvailabilityProvider>().associateBy { it.tenant.host }
            for ((source, host) in poiRegistry.aspiraHostBySource()) {
                add(source to aspiraByHost.getValue(host))
            }

            val reserveAmericaBySource = providers.filterIsInstance<ReserveAmericaAvailabilityProvider>().associateBy { it.tenant.source }
            for (config in poiRegistry.reserveAmericaSources()) {
                add(config.source to reserveAmericaBySource.getValue(config.source))
            }

            val reserveCaliforniaSources = poiRegistry.reserveCaliforniaSources()
            if (reserveCaliforniaSources.isNotEmpty()) {
                addSourceBindings(
                    provider = providers.singleAvailabilityProviderById(AvailabilityProviderId.RESERVECALIFORNIA),
                    sources = reserveCaliforniaSources,
                )
            }
        }

    val duplicates =
        pairs
            .groupBy { it.first }
            .filterValues { it.size > 1 }
            .keys
    require(duplicates.isEmpty()) {
        "duplicate availability provider source bindings: ${duplicates.sorted()}"
    }
    return pairs.associate { it }
}

internal fun Map<String, AvailabilityProvider>.availabilityProviderFor(row: CampsiteProviderRefRow): AvailabilityProvider? =
    availabilityProviderForSource(row.source)

internal fun Map<String, AvailabilityProvider>.availabilityProviderFor(
    row: CampsiteProviderRefRow,
    ref: ProviderRef,
): AvailabilityProvider? =
    availabilityProviderForSource(row.source)
        ?.takeIf { it.supportsRef(ref) }

internal fun Map<String, AvailabilityProvider>.availabilityProviderForSource(source: String): AvailabilityProvider? =
    this[source]?.takeIf { it.isEnabled() }

private fun MutableList<Pair<String, AvailabilityProvider>>.addSourceBindings(
    provider: AvailabilityProvider,
    sources: Iterable<String>,
) {
    sources.distinct().forEach { source ->
        add(source to provider)
    }
}

private inline fun <reified T : AvailabilityProvider> List<AvailabilityProvider>.singleAvailabilityProvider(
    id: AvailabilityProviderId,
): T =
    filterIsInstance<T>()
        .singleOrNull()
        ?: error("expected exactly one ${id.name.lowercase()} availability provider, found ${count { it.id == id }}")

private fun List<AvailabilityProvider>.singleAvailabilityProviderById(id: AvailabilityProviderId): AvailabilityProvider =
    singleOrNull { it.id == id }
        ?: error("expected exactly one ${id.name.lowercase()} availability provider, found ${count { it.id == id }}")
