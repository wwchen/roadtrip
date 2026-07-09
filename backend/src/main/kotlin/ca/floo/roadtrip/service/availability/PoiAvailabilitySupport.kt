package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PoiDetailRow
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser

internal class PoiAvailabilitySupport(
    private val providerRefs: CampsiteProviderRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
) {
    fun supports(row: PoiDetailRow): Boolean {
        if (row.category != CAMPGROUND_CATEGORY) return false
        return providerRefs.findProviderRefCandidates(row.id).any { candidate ->
            val ref = ProviderRefParser.parse(candidate.providerRefJson) ?: return@any false
            availabilityProviders
                .forPoi(candidate, ref)
                ?.capabilities
                ?.supportsAvailability == true
        }
    }

    private companion object {
        private const val CAMPGROUND_CATEGORY = "campground"
    }
}
