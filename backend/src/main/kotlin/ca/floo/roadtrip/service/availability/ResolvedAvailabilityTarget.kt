package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.service.reservation.ReservationProvider

internal data class ResolvedAvailabilityTarget(
    val reservable: Reservable,
    val provider: ReservationProvider,
    val parentRef: ProviderRef,
    val parentPoiId: Long,
    val dateContext: PoiDateContext,
)
