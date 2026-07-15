package ca.floo.roadtrip.models.booking

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.domain.ProviderRef

/**
 * Provider-neutral target for booking actions. The parent ref is the
 * campground/facility booking context; the campsite ref is the concrete site
 * or unit being added to a cart.
 */
data class BookingTarget(
    val providerId: BookingProviderId,
    val parentRef: ProviderRef,
    val campsiteRef: CatalogCampsiteRef,
)
