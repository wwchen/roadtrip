package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.BulkPoiSummaryDto
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.StreamWindowState
import ca.floo.roadtrip.service.api.campsiteWindowStatuses
import ca.floo.roadtrip.service.api.classifyStreamWindowState

/** A campground with at most this many campsites open for the whole stay reads as "few left". */
internal const val FEW_LEFT_MAX_SITES = 2

/**
 * Only the campsites that sleep [groupSize]. A campsite with no stated capacity
 * stays in: no data is not a miss, the same rule the campground filter applies
 * to the campground's own `max_people`.
 */
internal fun List<Campsite>.fittingGroup(groupSize: Int?): List<Campsite> {
    if (groupSize == null) return this
    return filter { campsite -> campsite.maxPeople.let { it == null || it >= groupSize } }
}

/**
 * How the slice's stay looks, over the slice's own campsites.
 *
 * A campsite is open for the stay when every night of the window is bookable
 * online — `first_come` is not, because nobody can reserve it. The status is
 * decided here rather than in a browser, from the same per-night statuses the
 * week grid fuses: open, or few left. With nothing open, only the campsites the
 * provider said anything about are judged — one it said nothing about is not in
 * the answer — and the stay is:
 *
 * - closed for the season, when every one of them is closed every night (the
 *   fused week's own rule);
 * - full, when every one of them has a night that rules it out — taken, closed,
 *   walk-up only;
 * - otherwise no data: some site has nights the provider left unanswered and no
 *   night against it, so it may well be open. A stay that runs into a month the
 *   provider did not return is the usual case, and calling it full would be a
 *   guess.
 */
internal fun PoiAvailabilitySlice.staySummary(): BulkPoiSummaryDto {
    val batch = batch
    if (batch == null || campsites.isEmpty()) {
        return BulkPoiSummaryDto(status = StayAvailabilityStatus.NO_DATA, openSites = 0, checkedSites = campsites.size)
    }
    val observationsByCampsite = batch.observations.groupBy { it.campsiteId }
    val streams =
        campsites.map { campsite ->
            campsiteWindowStatuses(startDate, endDate, observationsByCampsite[campsite.id].orEmpty())
        }
    val openSites = streams.count { nights -> nights.all { it.isOnlineBookable } }
    val answered = streams.filter { nights -> nights.any { it != AvailabilityStatus.UNKNOWN } }
    val status =
        when {
            openSites > FEW_LEFT_MAX_SITES -> StayAvailabilityStatus.OPEN
            openSites > 0 -> StayAvailabilityStatus.FEW_LEFT
            answered.isEmpty() -> StayAvailabilityStatus.NO_DATA
            answered.all { classifyStreamWindowState(it) == StreamWindowState.CLOSED_FOR_SEASON } ->
                StayAvailabilityStatus.CLOSED_FOR_SEASON
            answered.all { nights -> nights.any { it.rulesOutStay } } -> StayAvailabilityStatus.FULL
            else -> StayAvailabilityStatus.NO_DATA
        }
    return BulkPoiSummaryDto(
        status = status,
        openSites = openSites,
        checkedSites = campsites.size,
        cache = batch.cacheBlock,
    )
}

/** A night the provider answered that no booking can get past. */
private val AvailabilityStatus.rulesOutStay: Boolean
    get() = this != AvailabilityStatus.UNKNOWN && !isOnlineBookable
