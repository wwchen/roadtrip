package ca.floo.roadtrip.service.ref

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef

sealed interface RefValue {
    data class PoiId(
        val id: Long,
    ) : RefValue

    data class CampgroundId(
        val id: Long,
    ) : RefValue

    data class CampsiteId(
        val id: Long,
    ) : RefValue

    data class CampgroundDataRef(
        val ref: DataProviderRef,
    ) : RefValue

    data class CampsiteDataRef(
        val ref: DataProviderRef,
    ) : RefValue

    data class CampgroundBookingRef(
        val ref: BookingProviderRef,
    ) : RefValue

    data class CampsiteBookingRef(
        val ref: BookingProviderRef,
    ) : RefValue
}
