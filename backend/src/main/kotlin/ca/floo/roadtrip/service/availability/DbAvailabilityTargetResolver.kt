package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.availability.provider.availabilityProviderId

internal class DbAvailabilityTargetResolver(
    private val providerRefs: CampsiteProviderRepo,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
    private val dateResolver: AvailabilityDateResolver,
) : AvailabilityTargetResolver {
    private data class ResolvedRow(
        val poiId: Long,
        val row: CampsiteProviderRefRow,
        val candidate: ProviderCandidate,
    )

    override fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget? {
        val poiIds = campsitesRepo.poiIdsForCampsite(campsite.id)
        if (poiIds.isEmpty()) return null

        val providerRefsByPoiId = providerRefs.findProviderRefCandidates(poiIds)
        val resolvedRows: List<ResolvedRow> =
            poiIds
                .asSequence()
                .flatMap { poiId -> providerRefsByPoiId[poiId].orEmpty().asSequence().map { row -> poiId to row } }
                .mapNotNull { (poiId, row) ->
                    val candidate = buildCandidate(row, campsite) ?: return@mapNotNull null
                    ResolvedRow(poiId = poiId, row = row, candidate = candidate)
                }.toList()

        val head = resolvedRows.firstOrNull() ?: return null

        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = head.candidate.provider,
            parentRef = head.candidate.parentRef,
            catalogRef = head.candidate.catalogRef,
            parentPoiId = head.poiId,
            dateContext = dateResolver.context(lat = head.row.lat, lng = head.row.lng),
            candidates = resolvedRows.map { it.candidate },
        )
    }

    private fun buildCandidate(
        row: CampsiteProviderRefRow,
        campsite: CampsiteAvailabilityTarget,
    ): ProviderCandidate? {
        val ref = ProviderRefParser.parse(row.providerRefJson) ?: return null
        val provider = availabilityProviders.forPoi(row, ref) ?: return null
        return ProviderCandidate(
            provider = provider,
            parentRef = ref,
            catalogRef = catalogRefFor(campsite, provider.id),
        )
    }

    private fun catalogRefFor(
        campsite: CampsiteAvailabilityTarget,
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
