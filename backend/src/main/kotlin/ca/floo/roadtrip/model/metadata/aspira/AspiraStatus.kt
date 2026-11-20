package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.model.availability.AvailabilityStatus

object AspiraStatus {
    const val AVAILABLE = 0
    const val UNAVAILABLE = 1
    const val CLOSED = 2
    const val NON_RESERVABLE = 3
    const val SITE_CLOSED = 4
    const val OTHER_BOOKING_CATEGORY = 5

    /** Not a wire code: a row Aspira sent with no usable code. */
    const val UNKNOWN = Int.MIN_VALUE

    fun classify(code: Int): AvailabilityStatus? =
        when (code) {
            AVAILABLE -> AvailabilityStatus.AVAILABLE
            UNAVAILABLE -> AvailabilityStatus.RESERVED
            CLOSED -> AvailabilityStatus.CLOSED
            NON_RESERVABLE -> AvailabilityStatus.FIRST_COME
            SITE_CLOSED -> AvailabilityStatus.CLOSED
            OTHER_BOOKING_CATEGORY -> AvailabilityStatus.UNKNOWN
            UNKNOWN -> AvailabilityStatus.UNKNOWN
            else -> null
        }

    /** `/api/occupancy` answers 2 for booked and closed alike. */
    fun classifyOccupancy(code: Int): AvailabilityStatus? = if (code == CLOSED) AvailabilityStatus.RESERVED else classify(code)
}
