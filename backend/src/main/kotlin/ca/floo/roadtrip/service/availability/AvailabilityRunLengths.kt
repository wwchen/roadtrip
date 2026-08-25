package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CampsiteDayObservation

/**
 * Longest run of consecutive dates that are online-bookable. Input is sorted
 * before scanning, and a gap in dates breaks a run even when both sides are
 * bookable.
 */
fun longestRunNights(observations: List<CampsiteDayObservation>): Int {
    val bookable =
        observations
            .filter { it.status.isOnlineBookable }
            .map { it.date }
            .distinct()
            .sorted()
    if (bookable.isEmpty()) return 0

    var longest = 1
    var current = 1
    for (index in 1 until bookable.size) {
        current = if (bookable[index] == bookable[index - 1].plusDays(1)) current + 1 else 1
        if (current > longest) longest = current
    }
    return longest
}
