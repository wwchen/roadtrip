package ca.floo.roadtrip.model.availability

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef

data class CampgroundObservations(
    val campgroundRef: BookingProviderRef?,
    val observations: List<CampsiteDayObservation>,
)
