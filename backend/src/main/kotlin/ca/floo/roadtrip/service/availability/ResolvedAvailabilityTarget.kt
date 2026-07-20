package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

internal data class ResolvedAvailabilityTarget(
    val campsite: Campsite,
    val provider: AvailabilityProvider,
    val campground: Campground,
    val parentPoiId: Long,
    val dateContext: PoiDateContext,
    val candidates: List<AvailabilityProvider> = listOf(provider),
) {
    val parentRef: BookingProviderRef?
        get() = provider.parentRefFor(campground)
}
