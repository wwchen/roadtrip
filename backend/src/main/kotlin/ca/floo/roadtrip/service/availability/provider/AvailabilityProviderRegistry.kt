package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.models.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.models.domain.ProviderRef

data class AvailabilityProviderBinding(
    val source: String,
    val provider: AvailabilityProvider,
)

/**
 * Holds the live availability-provider adapters and dispatches a selected
 * `(vendor_ref.vendor, provider_ref)` pair to the right one.
 *
 * The registry indexes DI-provided source bindings; it does not construct
 * provider adapters. Once built, the registry exposes a single lookup — routes
 * and the watch poller never see the source string, and adapters never see the
 * source either.
 *
 * Key shape note: a single [AvailabilityProviderId] value can map to multiple
 * adapter *instances* (Aspira NextGen runs three tenants — PC/BC/WA — that
 * share a wire shape but have different hosts, caches, and campsite
 * vendor codes). The registry is keyed by the catalog source slug (`vendor_refs.vendor` tenant key), not by id, so
 * each tenant's source resolves to its own adapter while the public
 * provider id stays vendor-shaped.
 *
 * Held as a singleton in the application dependency graph; safe to share across
 * coroutines.
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
        fun fromBindings(bindings: List<AvailabilityProviderBinding>): AvailabilityProviderRegistry {
            val duplicates =
                bindings
                    .groupBy { it.source }
                    .filterValues { it.size > 1 }
                    .keys
            require(duplicates.isEmpty()) {
                "duplicate availability provider source bindings: ${duplicates.sorted()}"
            }
            return AvailabilityProviderRegistry(
                adaptersBySource = bindings.associate { it.source to it.provider },
            )
        }
    }
}
