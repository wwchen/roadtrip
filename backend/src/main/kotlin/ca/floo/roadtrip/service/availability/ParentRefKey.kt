package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.ProviderRef

/** Renders a vendor's call-unit id as text for observability. Not
 *  provider dispatch — just formatting a value already picked by the
 *  batcher's grouping key, so this `when` is not a capability leak. */
internal fun parentRefKey(ref: ProviderRef): String =
    when (ref) {
        is ProviderRef.RecGov -> ref.recgovId
        is ProviderRef.Campflare -> ref.campgroundId
        is ProviderRef.Aspira -> ref.mapId.toString()
        is ProviderRef.ReserveAmerica -> ref.parkId
        is ProviderRef.ReserveCalifornia -> ref.facilityIds.joinToString(",")
    }
