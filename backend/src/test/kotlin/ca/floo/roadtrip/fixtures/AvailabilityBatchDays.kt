package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.service.api.campsiteWindowStatuses
import ca.floo.roadtrip.service.api.rollupStatus
import java.time.temporal.ChronoUnit

/**
 * One date of a batch's window, read the way the fused week reads it: every
 * campsite the adapter keyed an observation to, at its latest status for that
 * date and `UNKNOWN` where it said nothing, ascending by id. [offset] counts
 * from the batch's own start date.
 */
fun AvailabilityObservationBatch.statusesOn(offset: Int): Map<Long, AvailabilityStatus> =
    observations
        .mapNotNull { observation -> observation.campsiteId?.let { it to observation } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .mapValues { (_, forCampsite) -> campsiteWindowStatuses(startDate, endDate, forCampsite)[offset] }

/** The campsites the batch reports bookable online on that date, ascending. */
fun AvailabilityObservationBatch.availableIdsOn(offset: Int): List<Long> =
    statusesOn(offset)
        .filterValues { it.isOnlineBookable }
        .keys
        .toList()

/** That date's rollup across those campsites, by the rule the fused week uses. */
fun AvailabilityObservationBatch.rollupOn(offset: Int): AvailabilityStatus = rollupStatus(statusesOn(offset).values)

/** The dates of the batch's window with at least one campsite bookable online. */
fun AvailabilityObservationBatch.bookableDates(): List<String> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .filter { availableIdsOn(it).isNotEmpty() }
        .map { startDate.plusDays(it.toLong()).toString() }
