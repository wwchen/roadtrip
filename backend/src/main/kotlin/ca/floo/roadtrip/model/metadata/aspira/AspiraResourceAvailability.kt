package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.model.availability.AvailabilityStatus

/**
 * Aspira's `resourceAvailabilities[*].availability` and `/api/occupancy`
 * resource rows use a different code family than `mapAvailabilities` and
 * `mapLinkAvailabilities`: live Parks Canada grid data shows `0` is the
 * bookable resource code, while nonzero resource codes are not bookable for
 * the requested date/equipment search.
 */
object AspiraResourceAvailability {
    const val AVAILABLE = 0
    const val UNKNOWN = Int.MIN_VALUE

    fun classify(code: Int): AvailabilityStatus =
        when (code) {
            AVAILABLE -> AvailabilityStatus.AVAILABLE
            UNKNOWN -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.RESERVED
        }
}
