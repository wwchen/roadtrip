package ca.floo.roadtrip.model.booking

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef

data class BookingTarget(
    val providerId: BookingProvider,
    val parentRef: BookingProviderRef,
    val campsiteId: Long,
    val vendorSiteId: String,
)
