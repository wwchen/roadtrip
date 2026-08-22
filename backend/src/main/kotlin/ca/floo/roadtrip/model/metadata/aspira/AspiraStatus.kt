package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.model.availability.AvailabilityStatus

/**
 * Aspira availability codes. One family across map, map-link, and resource
 * rows: 0 is bookable, 2 is closed, any other nonzero is not bookable.
 * Evidence and verification method live in docs/reservation-providers/aspira.md.
 */
object AspiraStatus {
    const val AVAILABLE = 0
    const val UNAVAILABLE = 1
    const val CLOSED = 2

    /** Not a wire code: a row Aspira sent with no usable code. */
    const val UNKNOWN = Int.MIN_VALUE

    fun classify(code: Int): AvailabilityStatus =
        when (code) {
            AVAILABLE -> AvailabilityStatus.AVAILABLE
            CLOSED -> AvailabilityStatus.CLOSED
            UNKNOWN -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.RESERVED
        }

    /** `/api/occupancy` answers 2 for booked and closed alike. */
    fun classifyOccupancy(code: Int): AvailabilityStatus =
        when (code) {
            AVAILABLE -> AvailabilityStatus.AVAILABLE
            UNKNOWN -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.RESERVED
        }
}
