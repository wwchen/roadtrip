package ca.floo.roadtrip.models.metadata.aspira

import ca.floo.roadtrip.models.availability.AvailabilityStatus

/**
 * Aspira NextGen returns availability as integer codes per (map, day). The
 * codes are not publicly documented, so this mapping is observed across
 * multiple parks (Banff peak summer, Yoho, Glacier, winter dates) on
 * 2026-06-07. If Aspira ever introduces a new code, surface it as
 * [AvailabilityStatus.UNKNOWN] rather than guessing bookability.
 *
 * Mapping rationale:
 *   1, 2  → AVAILABLE   — at least one site bookable, no constraints
 *   3, 7  → AVAILABLE   — mixed: some sub-areas/sites avail, others not
 *   6     → MOSTLY_BOOKED — observed when peak-summer Banff has only a few
 *                          slots left; still online-bookable
 *   0     → NO_DATA     — no provider data for this date/resource
 *   5     → CLOSED      — explicit closed (winter, end-of-season)
 *   else  → UNKNOWN     — unfamiliar code, not assumed available or closed
 */
object AspiraStatus {
    const val NO_DATA = 0
    const val AVAILABLE = 1
    const val LIMITED = 2
    const val PARTIAL = 3
    const val UNAVAILABLE = 5
    const val MOSTLY_BOOKED = 6
    const val MIXED = 7

    /** Map an Aspira integer code to the canonical availability status. */
    fun classify(code: Int): AvailabilityStatus =
        when (code) {
            AVAILABLE, LIMITED, PARTIAL, MIXED, MOSTLY_BOOKED -> AvailabilityStatus.AVAILABLE
            UNAVAILABLE -> AvailabilityStatus.CLOSED
            NO_DATA -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.UNKNOWN
        }
}
