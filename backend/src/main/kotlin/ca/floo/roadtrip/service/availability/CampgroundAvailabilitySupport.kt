package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser

internal class CampgroundAvailabilitySupport(
    private val providerRefs: CampsiteProviderRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
) {
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
                availabilityProviders
                    .forSource(candidate.source)
                    ?.takeIf { it.canHandle(ref) && it.capabilities.supportsAvailability }
                    ?.id
                    ?.name
                    ?.lowercase()
            }
}
