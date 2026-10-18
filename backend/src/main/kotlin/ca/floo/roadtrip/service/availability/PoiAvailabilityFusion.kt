package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityCellDto
import ca.floo.roadtrip.model.api.AvailabilityDayDto
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.service.api.StreamWindowState
import ca.floo.roadtrip.service.api.classifyStreamWindowState
import ca.floo.roadtrip.service.api.rollupStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Fuses one POI's campsite streams into the campground week the wire carries:
 * the day's rollup over every campsite, one cell per campsite, and the
 * watchability of both. Pure — the streams are already derived, and the one
 * polling answer its serving provider gives arrives with them.
 */
internal fun fuseWindow(
    streams: List<CampsiteStream>,
    window: ResolvedDateWindow,
    earliestDate: LocalDate,
    pollingSupported: Boolean,
    cache: AvailabilityCacheBlock?,
    season: AvailabilitySeasonBlock?,
): FusedWindow {
    if (streams.isEmpty()) {
        return FusedWindow(AvailabilityWindowState.EMPTY, season = null, cache = null, days = emptyList())
    }

    val closedForSeason =
        streams.all { classifyStreamWindowState(it.statuses) == StreamWindowState.CLOSED_FOR_SEASON }
    val dayCount = ChronoUnit.DAYS.between(window.startDate, window.endDate).toInt()
    return FusedWindow(
        state = if (closedForSeason) AvailabilityWindowState.CLOSED_FOR_SEASON else AvailabilityWindowState.SUCCESS,
        season = season.takeIf { closedForSeason },
        cache = cache,
        // A closed week has no grid to draw — clients gate on `state` — so the
        // days are the wire's own dead weight.
        days =
            if (closedForSeason) {
                emptyList()
            } else {
                (0 until dayCount).map { offset ->
                    fusedDay(
                        streams = streams,
                        pollingSupported = pollingSupported,
                        offset = offset,
                        date = window.startDate.plusDays(offset.toLong()),
                        earliestDate = earliestDate,
                    )
                }
            },
    )
}

/** One date across every stream: cells in ascending campsite id, then the rollup. */
private fun fusedDay(
    streams: List<CampsiteStream>,
    pollingSupported: Boolean,
    offset: Int,
    date: LocalDate,
    earliestDate: LocalDate,
): AvailabilityDayDto {
    val cells =
        streams.associate { stream ->
            stream.campsiteId to
                AvailabilityCellDto.of(stream.statuses[offset], pollingSupported, date, earliestDate)
        }
    return AvailabilityDayDto(
        date = date.toString(),
        status = rollupStatus(cells.values.map { it.status }),
        watchable = cells.values.any { it.watchable },
        cells = cells,
    )
}
