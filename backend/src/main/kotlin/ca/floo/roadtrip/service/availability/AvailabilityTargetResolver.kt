package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.Campsite
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.CatalogCampsiteRef
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.availability.provider.availabilityProviderId

/**
 * Resolves an already-loaded [Campsite] to the provider adapter, parent
 * provider ref, and date context needed to fetch its availability. A port so
 * the request path can be unit-tested with an in-memory fake;
 * [DbAvailabilityTargetResolver] is the production, DB-backed implementation.
 */
internal interface AvailabilityTargetResolver {
    /** Resolve an already-loaded campsite, or null when it has no resolvable
     *  availability provider. */
    fun resolve(campsite: Campsite): ResolvedAvailabilityTarget?
}

internal class DbAvailabilityTargetResolver(
    private val providerRefs: CampsiteProviderRepo,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
) : AvailabilityTargetResolver {
    private data class ParentCandidate(
        val poiId: Long,
        val row: CampsiteProviderRefRow,
        val provider: AvailabilityProvider,
        val ref: ProviderRef,
    )

    override fun resolve(campsite: Campsite): ResolvedAvailabilityTarget? {
        val poiIds = campsitesRepo.poiIdsForCampsite(campsite.id)
        if (poiIds.isEmpty()) return null

        val providerRefsByPoiId = providerRefs.findProviderRefCandidates(poiIds)
        val parent =
            poiIds
                .asSequence()
                .flatMap { poiId -> providerRefsByPoiId[poiId].orEmpty().asSequence().map { row -> poiId to row } }
                .mapNotNull { (poiId, row) -> parentCandidate(poiId, row) }
                .firstOrNull() ?: return null

        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = parent.provider,
            parentRef = parent.ref,
            catalogRef = catalogRefFor(campsite, parent.provider.id),
            parentPoiId = parent.poiId,
            dateContext = dateResolver.context(lat = parent.row.lat, lng = parent.row.lng),
        )
    }

    private fun parentCandidate(
        poiId: Long,
        row: CampsiteProviderRefRow,
    ): ParentCandidate? {
        val ref = ProviderRefParser.parse(row.providerRefJson) ?: return null
        val provider = availabilityProviders.forPoi(row, ref) ?: return null
        return ParentCandidate(
            poiId = poiId,
            row = row,
            provider = provider,
            ref = ref,
        )
    }

    private fun catalogRefFor(
        campsite: Campsite,
        providerId: AvailabilityProviderId,
    ): CatalogCampsiteRef {
        val fallback = campsite.toCatalogCampsiteRef()
        val ref =
            providerRefs
                .findCampsiteProviderRefs(campsite.id)
                .asSequence()
                .mapNotNull { ProviderRefParser.parse(it.providerRefJson) }
                .firstOrNull { it.availabilityProviderId() == providerId }
                ?: return fallback
        return ref.toCatalogCampsiteRef(campsiteId = campsite.id, fallback = fallback)
    }

    private fun ProviderRef.toCatalogCampsiteRef(
        campsiteId: Long,
        fallback: CatalogCampsiteRef,
    ): CatalogCampsiteRef =
        when (this) {
            is ProviderRef.RecGov ->
                CatalogCampsiteRef(
                    campsiteId = campsiteId,
                    vendorId = recgovId,
                )
            is ProviderRef.Campflare ->
                CatalogCampsiteRef(
                    campsiteId = campsiteId,
                    vendorId = campgroundId,
                )
            is ProviderRef.Aspira ->
                fallback.copy(
                    campsiteId = campsiteId,
                    mapId = mapId,
                    resourceLocationId = resourceLocationId,
                )
            else -> fallback.copy(campsiteId = campsiteId)
        }
}
