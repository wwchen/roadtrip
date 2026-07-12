package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser

internal class PoiAvailabilitySupport(
    private val providerRefs: CampsiteProviderRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
) {
    fun supportsPoi(poiId: Long): Boolean =
        providerRefs.findProviderRefCandidates(poiId).any { candidate ->
            val ref = ProviderRefParser.parse(candidate.providerRefJson) ?: return@any false
            availabilityProviders
                .forPoi(candidate, ref)
                ?.capabilities
                ?.supportsAvailability == true
        }

    /**
     * Vendor identifier of the resolver's preferred availability candidate for
     * [poiId], or `null` when no candidate exists. Same ordering as
     * [CampsiteProviderRepo.findProviderRefCandidates].
     */
    fun preferredAvailabilityProvider(poiId: Long): String? = providerRefs.findProviderRefCandidates(poiId).firstOrNull()?.source
}
