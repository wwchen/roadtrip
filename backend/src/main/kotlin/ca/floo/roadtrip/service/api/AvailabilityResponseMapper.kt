package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.api.AvailabilityErrorDto
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Provider-agnostic reads over a normalized observation batch, plus the two
// response fragments every availability endpoint shares. Each adapter is
// responsible for turning its upstream payload into CampsiteDayObservations;
// everything below is the same whichever vendor produced them.

/**
 * One campsite's window as a status per date: the latest observation for each
 * date, `UNKNOWN` where the campsite went unobserved.
 */
internal fun campsiteWindowStatuses(
    startDate: LocalDate,
    endDate: LocalDate,
    observations: List<CampsiteDayObservation>,
): List<AvailabilityStatus> {
    val latestByDate = HashMap<LocalDate, CampsiteDayObservation>()
    for (observation in observations) {
        if (observation.date.isBefore(startDate) || !observation.date.isBefore(endDate)) continue
        val current = latestByDate[observation.date]
        if (current == null || !observation.observedAt.isBefore(current.observedAt)) {
            latestByDate[observation.date] = observation
        }
    }
    val days = ChronoUnit.DAYS.between(startDate, endDate).toInt()
    return (0 until days).map { offset ->
        latestByDate[startDate.plusDays(offset.toLong())]?.status ?: AvailabilityStatus.UNKNOWN
    }
}

fun rollupStatus(statuses: Iterable<AvailabilityStatus>): AvailabilityStatus {
    val values = statuses.toList()
    if (values.isEmpty()) return AvailabilityStatus.UNKNOWN
    return when {
        values.any { it == AvailabilityStatus.AVAILABLE } -> AvailabilityStatus.AVAILABLE
        values.any { it == AvailabilityStatus.FIRST_COME } -> AvailabilityStatus.FIRST_COME
        values.any { it == AvailabilityStatus.UNKNOWN } -> AvailabilityStatus.UNKNOWN
        values.any { it == AvailabilityStatus.RESERVED } -> AvailabilityStatus.RESERVED
        values.all { it == AvailabilityStatus.CLOSED } -> AvailabilityStatus.CLOSED
        else -> AvailabilityStatus.UNKNOWN
    }
}

/**
 * Whether one campsite's window is closed for the season: every night of it
 * reads closed. A window with no nights in it says nothing, so it is not.
 */
internal fun isClosedForSeason(statuses: List<AvailabilityStatus>): Boolean =
    statuses.isNotEmpty() && statuses.all { it == AvailabilityStatus.CLOSED }

/** The season block as the wire carries it; null stays absent rather than `{}`. */
fun seasonElement(block: AvailabilitySeasonBlock?): JsonElement? = block?.let { embeddedApiJson.encodeToJsonElement(it) }

fun availabilityErrorDto(
    error: String,
    detail: String? = null,
    upstreamStatus: Int? = null,
    earliestDate: String? = null,
    timeZone: String? = null,
    latestDate: String? = null,
    maxDays: Int? = null,
): AvailabilityErrorDto =
    AvailabilityErrorDto(
        error = error,
        detail = detail,
        upstreamStatus = upstreamStatus,
        earliestDate = earliestDate,
        timeZone = timeZone,
        latestDate = latestDate,
        maxDays = maxDays,
    )
