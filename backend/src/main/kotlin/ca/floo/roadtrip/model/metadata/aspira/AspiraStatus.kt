package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.model.availability.AvailabilityStatus

/**
 * Aspira NextGen returns availability as integer codes per day. One code
 * family covers every row shape — `mapAvailabilities`, `mapLinkAvailabilities`,
 * `resourceAvailabilities[*].availability`, and `/api/occupancy`'s
 * `resourceOccupancy[*].availability`. **Zero is the bookable code.**
 *
 * Verified live on 2026-08-21 against two tenants:
 *
 *   camping.bcparks.ca, Alice Lake mapId -2147483647. The vendor's own booking
 *     calendar was read for 2026-08-22..2026-09-04; across that window
 *     resources 38 and 39 answer 0 on 2026-08-31 and 1 on every other day,
 *     matching the calendar's single open cell for each. (The capture runs to
 *     09-10 and both answer 0 again on 09-07, past what the calendar was read
 *     for — so that day is evidence of nothing either way, and
 *     `AspiraStatusGroundTruthTest` asserts only through 09-04.)
 *
 *   reservation.pc.gc.ca, Tunnel Mountain Village 1 loop A mapId -2147483621,
 *     same window: 1 on Sat 08-22, Sat 08-29, Sat 09-05 and Sun 09-06, 0 on
 *     every other day — peak-season weekends booked out, weekdays open.
 *
 *   washington.goingtocamp.com, Seaquest mapId -2147483498, 2026-08-24..09-13:
 *     a mean of 37.2 sites answer 0 on weekdays against 7.3 on Fri/Sat, and
 *     Fri 08-28 and Sat 09-05 answer 0 for none of the 69 sites.
 *
 *   Seasonal parks (BC, PC) answer [CLOSED] for every row on winter and
 *   beyond-horizon dates; year-round WA parks mix [CLOSED] with 0 there, so
 *   the code is per-resource "not open for booking", not "park is shut".
 *
 * Codes beyond these do occur and are deliberately not guessed at: WA answers
 * 5 for its yurts, group site, and picnic shelter in every window sampled —
 * inventory that needs a different equipment/booking category than the
 * any-equipment search we send, so it is not bookable *for this request*
 * rather than closed. (An earlier revision of this file called 5 "closed" on
 * the strength of an unverified observation.) 4 shows up rarely on both PC and
 * WA. Both fall through to [AvailabilityStatus.RESERVED].
 *
 * Anything else nonzero is a real row that is not bookable for the requested
 * date/equipment search, so it maps to [AvailabilityStatus.RESERVED] rather
 * than being guessed as open.
 *
 * The two endpoints agree on zero but not on how much they say about the rest:
 * `/api/availability/map` separates booked ([UNAVAILABLE]) from closed
 * ([CLOSED]), while `/api/occupancy` returns [CLOSED]'s code for both — on
 * 2026-08-21 Alice Lake answered every one of its 109 occupancy rows with 2
 * for a fully booked summer date and for a winter date alike. Hence the two
 * entry points below; pick the one named for the endpoint being read.
 */
object AspiraStatus {
    const val AVAILABLE = 0
    const val UNAVAILABLE = 1
    const val CLOSED = 2

    /** Not a wire code: stands in for a row Aspira sent with no usable code. */
    const val UNKNOWN = Int.MIN_VALUE

    /** Classify a `/api/availability/map` map, map-link, or resource code. */
    fun classify(code: Int): AvailabilityStatus =
        when (code) {
            AVAILABLE -> AvailabilityStatus.AVAILABLE
            CLOSED -> AvailabilityStatus.CLOSED
            UNKNOWN -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.RESERVED
        }

    /** Classify a `/api/occupancy` resource code, which cannot tell a booked
     *  site from a closed one. */
    fun classifyOccupancy(code: Int): AvailabilityStatus =
        when (code) {
            AVAILABLE -> AvailabilityStatus.AVAILABLE
            UNKNOWN -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.RESERVED
        }
}
