package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PoiDetailRow
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry

internal class PoiAvailabilitySupport(
    private val providerRefs: CampsiteProviderRepo,
    private val reservationProviders: ReservationProviderRegistry,
) {
    fun supports(row: PoiDetailRow): Boolean {
        if (row.category != CAMPGROUND_CATEGORY) return false
        return providerRefs.findProviderRefCandidates(row.id).any { candidate ->
            val ref = ProviderRefParser.parse(candidate.providerRefJson) ?: return@any false
            reservationProviders
                .forPoi(candidate, ref)
                ?.capabilities
                ?.supportsAvailability == true
        }
    }

    private companion object {
        private const val CAMPGROUND_CATEGORY = "campground"
    }
}
