package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.availability.provider.availabilityProviderId

internal class CampgroundAvailabilitySupport(
    private val providerRefs: CampsiteProviderRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
) {
    fun supportsCampground(campgroundId: Long): Boolean =
        providerRefs.findCampgroundProviderRefCandidates(campgroundId).any { candidate ->
            val ref = ProviderRefParser.parse(candidate.providerRefJson) ?: return@any false
            availabilityProviders
                .forSource(candidate.source)
                ?.takeIf { it.canHandle(ref) }
                ?.capabilities
                ?.supportsAvailability == true
        }

    /**
     * Stable provider identifier of the resolver's preferred availability
     * candidate for [campgroundId], or `null` when no candidate exists. Same
     * ordering as [CampsiteProviderRepo.findCampgroundProviderRefCandidates].
     */
    fun preferredAvailabilityProvider(campgroundId: Long): String? =
        providerRefs
            .findCampgroundProviderRefCandidates(campgroundId)
            .firstNotNullOfOrNull { candidate ->
                ProviderRefParser
                    .parse(candidate.providerRefJson)
                    ?.availabilityProviderId()
                    ?.name
                    ?.lowercase()
            }
}
