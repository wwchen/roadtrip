package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.availability.provider.availabilityProviderForSource
import ca.floo.roadtrip.service.availability.provider.availabilityProvidersBySource

internal class CampgroundAvailabilitySupport(
    private val providerRefs: CampsiteProviderRepo,
    poiRegistry: PoiRegistry,
    availabilityProviders: List<AvailabilityProvider>,
) {
    private val providersBySource = availabilityProvidersBySource(poiRegistry, availabilityProviders)

    /**
     * Stable provider identifier of the resolver's preferred availability
     * candidate for [campgroundId], or `null` when no candidate exists. Same
     * ordering as [CampsiteProviderRepo.findCampgroundProviderRefCandidates].
     */
    fun preferredAvailabilityProvider(campgroundId: Long): String? =
        providerRefs
            .findCampgroundProviderRefCandidates(campgroundId)
            .firstNotNullOfOrNull { candidate ->
                val ref = ProviderRefParser.parse(candidate.providerRefJson) ?: return@firstNotNullOfOrNull null
                providersBySource
                    .availabilityProviderForSource(candidate.source)
                    ?.takeIf { it.supportsRef(ref) }
                    ?.id
                    ?.name
                    ?.lowercase()
            }
}
