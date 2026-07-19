package ca.floo.roadtrip.model.booking

import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef

/**
 * Provider-neutral target for booking actions. The parent ref is the
 * campground/facility booking context; the campsite ref is the concrete site
 * or unit being added to a cart.
 */
data class BookingTarget(
    val providerId: BookingProvider,
    val parentRef: BookingProviderRef,
    val campsiteRef: CatalogCampsiteRef,
)
