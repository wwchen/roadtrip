package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry

internal class AvailabilityTargetResolver(
    private val providerRefs: CampsiteProviderRepo,
    private val reservablesRepo: ReservableRepo,
    private val reservationProviders: ReservationProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
) {
    fun requireByRid(rid: ReservableId): ResolvedAvailabilityTarget {
        val reservable =
            reservablesRepo.findByRid(rid)
                ?: throw AvailabilityServiceError.NotFound
        return resolve(reservable)
            ?: throw AvailabilityServiceError.UnknownCampground
    }

    fun resolve(reservable: Reservable): ResolvedAvailabilityTarget? {
        val poiIds = reservablesRepo.poiIdsForReservable(reservable.id)
        if (poiIds.isEmpty()) return null

        val providerRefsByPoiId = providerRefs.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { providerRefsByPoiId[it] }
                .firstOrNull { row ->
                    reservationProviders.forPoi(row) != null && ProviderRefParser.parse(row.providerRefJson) != null
                } ?: return null

        val provider = reservationProviders.forPoi(parent) ?: return null
        val parentRef = ProviderRefParser.parse(parent.providerRefJson) ?: return null
        return ResolvedAvailabilityTarget(
            reservable = reservable,
            provider = provider,
            parentRef = parentRef,
            dateContext = dateResolver.context(lat = parent.lat, lng = parent.lng),
        )
    }
}
