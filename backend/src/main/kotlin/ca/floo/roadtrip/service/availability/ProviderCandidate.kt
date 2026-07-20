package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

/**
 * One resolvable (provider, campground, catalogRef) triple that could serve a
 * campsite's availability. The resolver enumerates provider refs attached to
 * the POI's campground row; downstream failover walks the list, so one
 * unreachable provider ref doesn't drop the whole campsite off the map.
 */
internal data class ProviderCandidate(
    val provider: AvailabilityProvider,
    val campground: Campground,
    val catalogRef: CatalogCampsiteRef,
) {
    val parentRef: BookingProviderRef?
        get() = provider.parentRefFor(campground)
}
