package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus

/**
 * Longest run of consecutive nights that are online-bookable, over one
 * campsite's statuses in date order. An unanswered night is `UNKNOWN` and so
 * breaks a run, as does any night that cannot be booked online.
 */
fun longestRunNights(statuses: List<AvailabilityStatus>): Int {
    var longest = 0
    var current = 0
    for (status in statuses) {
        current = if (status.isOnlineBookable) current + 1 else 0
        if (current > longest) longest = current
    }
    return longest
}
