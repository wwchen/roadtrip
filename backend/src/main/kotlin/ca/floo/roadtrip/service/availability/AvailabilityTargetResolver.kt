package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry

/**
 * Resolves a reservable (by rid, or from an already-loaded [Reservable]) to the
 * provider adapter, parent provider ref, and date context needed to fetch its
 * availability. A port so the request path can be unit-tested with an in-memory
 * fake; [DbAvailabilityTargetResolver] is the production, DB-backed implementation.
 */
internal interface AvailabilityTargetResolver {
    /** Resolve by rid, throwing [AvailabilityServiceError.NotFound] when the
     *  reservable is unknown and [AvailabilityServiceError.UnknownCampground]
     *  when it has no resolvable reservation provider. */
    fun requireByRid(rid: ReservableId): ResolvedAvailabilityTarget

    /** Resolve an already-loaded reservable, or null when it has no resolvable
     *  reservation provider. */
    fun resolve(reservable: Reservable): ResolvedAvailabilityTarget?
}

internal class DbAvailabilityTargetResolver(
    private val providerRefs: CampsiteProviderRepo,
    private val reservablesRepo: ReservableRepo,
    private val reservationProviders: ReservationProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
) : AvailabilityTargetResolver {
    override fun requireByRid(rid: ReservableId): ResolvedAvailabilityTarget {
        val reservable =
            reservablesRepo.findByRid(rid)
                ?: throw AvailabilityServiceError.NotFound
        return resolve(reservable)
            ?: throw AvailabilityServiceError.UnknownCampground
    }

    override fun resolve(reservable: Reservable): ResolvedAvailabilityTarget? {
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
