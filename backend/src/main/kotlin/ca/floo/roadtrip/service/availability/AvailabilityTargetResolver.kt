package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
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
    private val campsitesRepo: CampsiteRepo,
    private val reservationProviders: ReservationProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
) : AvailabilityTargetResolver {
    override fun requireByRid(rid: ReservableId): ResolvedAvailabilityTarget {
        val reservable =
            campsitesRepo.findByRid(rid)
                ?: throw AvailabilityServiceError.NotFound
        return resolve(reservable)
            ?: throw AvailabilityServiceError.UnknownCampground
    }

    override fun resolve(reservable: Reservable): ResolvedAvailabilityTarget? {
        val poiIds = campsitesRepo.poiIdsForCampsite(reservable.id)
        if (poiIds.isEmpty()) return null

        val providerRefsByPoiId = providerRefs.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { poiId -> providerRefsByPoiId[poiId]?.let { poiId to it } }
                .firstOrNull { (_, row) ->
                    reservationProviders.forPoi(row) != null && ProviderRefParser.parse(row.providerRefJson) != null
                } ?: return null
        val (parentPoiId, parentRow) = parent

        val provider = reservationProviders.forPoi(parentRow) ?: return null
        val parentRef = ProviderRefParser.parse(parentRow.providerRefJson) ?: return null
        return ResolvedAvailabilityTarget(
            reservable = reservable,
            provider = provider,
            parentRef = parentRef,
            parentPoiId = parentPoiId,
            dateContext = dateResolver.context(lat = parentRow.lat, lng = parentRow.lng),
        )
    }
}
