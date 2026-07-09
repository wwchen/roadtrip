package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import ca.floo.roadtrip.service.reservation.reservationProviderId

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
    private data class ParentCandidate(
        val poiId: Long,
        val row: CampsiteProviderRefRow,
        val provider: ReservationProvider,
        val ref: ProviderRef,
    )

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

        val providerRefsByPoiId = providerRefs.findProviderRefCandidates(poiIds)
        val parent =
            poiIds
                .asSequence()
                .flatMap { poiId -> providerRefsByPoiId[poiId].orEmpty().asSequence().map { row -> poiId to row } }
                .mapNotNull { (poiId, row) -> parentCandidate(poiId, row) }
                .firstOrNull() ?: return null

        return ResolvedAvailabilityTarget(
            reservable = reservable,
            provider = parent.provider,
            parentRef = parent.ref,
            catalogRef = catalogRefFor(reservable, parent.provider.id),
            parentPoiId = parent.poiId,
            dateContext = dateResolver.context(lat = parent.row.lat, lng = parent.row.lng),
        )
    }

    private fun parentCandidate(
        poiId: Long,
        row: CampsiteProviderRefRow,
    ): ParentCandidate? {
        val ref = ProviderRefParser.parse(row.providerRefJson) ?: return null
        val provider = reservationProviders.forPoi(row, ref) ?: return null
        return ParentCandidate(
            poiId = poiId,
            row = row,
            provider = provider,
            ref = ref,
        )
    }

    private fun catalogRefFor(
        reservable: Reservable,
        providerId: ReservationProviderId,
    ): CatalogReservableRef {
        val fallback = reservable.toCatalogReservableRef()
        val ref =
            providerRefs
                .findCampsiteProviderRefs(reservable.id)
                .asSequence()
                .mapNotNull { ProviderRefParser.parse(it.providerRefJson) }
                .firstOrNull { it.reservationProviderId() == providerId }
                ?: return fallback
        return ref.toCatalogReservableRef(canonicalRid = reservable.rid.encode(), fallback = fallback)
    }

    private fun ProviderRef.toCatalogReservableRef(
        canonicalRid: String,
        fallback: CatalogReservableRef,
    ): CatalogReservableRef =
        when (this) {
            is ProviderRef.RecGov ->
                CatalogReservableRef(
                    rid = canonicalRid,
                    vendorId = recgovId,
                )
            is ProviderRef.Campflare ->
                CatalogReservableRef(
                    rid = canonicalRid,
                    vendorId = campgroundId,
                )
            is ProviderRef.Aspira ->
                fallback.copy(
                    rid = canonicalRid,
                    mapId = mapId,
                    resourceLocationId = resourceLocationId,
                )
            else -> fallback.copy(rid = canonicalRid)
        }
}
