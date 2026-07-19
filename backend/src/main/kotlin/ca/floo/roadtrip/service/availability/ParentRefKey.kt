package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef

/** Renders a vendor's call-unit id as text for observability. Not
 *  provider dispatch — just formatting a value already picked by the
 *  batcher's grouping key, so this `when` is not a capability leak. */
internal fun parentRefKey(ref: BookingProviderRef): String =
    when (ref) {
        is BookingProviderRef.RecGov -> ref.facilityId
        is BookingProviderRef.Campflare -> ref.campgroundId
        is BookingProviderRef.Aspira -> ref.mapId.toString()
        is BookingProviderRef.ReserveAmerica -> ref.parkId
        is BookingProviderRef.ReserveCalifornia -> ref.facilityIds.joinToString(",")
    }
