package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.api.StaySummaryDto
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.campsiteWindowStatuses

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
 * - closed for the season, when every one of them is closed on at least one
 *   night: the season ends, or has not begun, within the stay. A stay that runs
 *   into the closure cannot be had whole, and "full" would send someone hunting
 *   for a cancellation that will never come;
 * - full, when every one of them has a night that rules it out — taken, closed,
 *   walk-up only;
 * - otherwise no data: some site has nights the provider left unanswered and no
 *   night against it, so it may well be open. A stay that runs into a month the
 *   provider did not return is the usual case, and calling it full would be a
 *   guess.
 *
 * The answer is dated by the oldest observation in the batch behind it, as an
 * instant: a stay is only as fresh as its stalest night, and the loader's own
 * cache age is relative to a clock the browser never saw. The batch is read
 * whole, so a campsite the group size left out, or a night outside the stay,
 * can date it too; that errs stale, never fresh. It is absent when no
 * observation backs the answer: no campsite was checked (the site type left none, or the
 * group size did), or campsites were but the provider returned nothing for the
 * window.
 */
internal fun PoiAvailabilitySlice.staySummary(): StaySummaryDto {
    val batch = batch
    if (batch == null || campsites.isEmpty()) {
        return StaySummaryDto(
            status = StayAvailabilityStatus.NO_DATA,
            openSites = 0,
            checkedSites = campsites.size,
            observedAt = null,
            fromCache = false,
        )
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
            answered.all { nights -> AvailabilityStatus.CLOSED in nights } -> StayAvailabilityStatus.CLOSED_FOR_SEASON
            answered.all { nights -> nights.any { it.rulesOutStay } } -> StayAvailabilityStatus.FULL
            else -> StayAvailabilityStatus.NO_DATA
        }
    return StaySummaryDto(
        status = status,
        openSites = openSites,
        checkedSites = campsites.size,
        observedAt = batch.observations.minOfOrNull { it.observedAt }?.toString(),
        fromCache = batch.cacheBlock.hit,
    )
}

/** A night the provider answered that no booking can get past. */
private val AvailabilityStatus.rulesOutStay: Boolean
    get() = this != AvailabilityStatus.UNKNOWN && !isOnlineBookable
