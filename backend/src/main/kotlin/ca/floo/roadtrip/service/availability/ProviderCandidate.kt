package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.CatalogCampsiteRef

/**
 * One resolvable (provider, parentRef, catalogRef) triple that could serve a
 * campsite's availability. The resolver enumerates every candidate in the
 * POI's match group (preferred availability source first, canonical winner
 * ahead of siblings — see [ca.floo.roadtrip.repo.CampsiteProviderRepo.findProviderRefCandidates]);
 * downstream failover walks the list, so a single unreachable vendor doesn't
 * drop the whole campsite off the map.
 */
internal data class ProviderCandidate(
    val provider: AvailabilityProvider,
    val parentRef: ProviderRef,
    val catalogRef: CatalogCampsiteRef,
)
