package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

/**
 * One resolvable (provider, parentRef, catalogRef) triple that could serve a
 * campsite's availability. The resolver enumerates provider refs attached to
 * the POI's campground row; downstream failover walks the list, so one
 * unreachable provider ref doesn't drop the whole campsite off the map.
 */
internal data class ProviderCandidate(
    val provider: AvailabilityProvider,
    val parentRef: ProviderRef,
    val catalogRef: CatalogCampsiteRef,
)
