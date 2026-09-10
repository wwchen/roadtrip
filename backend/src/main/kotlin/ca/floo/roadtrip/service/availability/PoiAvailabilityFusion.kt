package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityCellDto
import ca.floo.roadtrip.model.api.AvailabilityDayDto
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.service.api.StreamWindowState
import ca.floo.roadtrip.service.api.campsiteWindowStatuses
import ca.floo.roadtrip.service.api.classifyStreamWindowState
import ca.floo.roadtrip.service.api.rollupStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The campground week, fused from the per-campsite streams. */
internal data class FusedWindow(
    val state: AvailabilityWindowState,
    val season: AvailabilitySeasonBlock?,
    val cache: AvailabilityCacheBlock?,
    val days: List<AvailabilityDayDto>,
)

/**
 * One campsite's window, narrowed from the slice's batch: a status per date and
 * whether the stream is closed for the season. Polling support is not here — it
 * is the serving provider's answer, the same for every stream in the slice.
 */
internal class CampsiteStream(
    val campsiteId: Long,
    val statuses: List<AvailabilityStatus>,
    val closedForSeason: Boolean,
    val cache: AvailabilityCacheBlock,
)

/**
 * Fuses one POI's campsite streams into the campground week the wire carries:
 * the day's rollup over every campsite, one cell per campsite, and the
 * watchability of both. Pure — every input is already on the slice, including
 * the one polling answer its serving provider gives.
 */
internal fun fusePoiWindow(
    slice: PoiAvailabilitySlice,
    earliestDate: LocalDate,
): FusedWindow {
    val batch = slice.batch
    if (batch == null || slice.campsites.isEmpty()) {
        return FusedWindow(AvailabilityWindowState.EMPTY, season = null, cache = null, days = emptyList())
    }

    val observationsByCampsite = batch.observations.groupBy { it.campsiteId }
    val streams =
        slice.campsites.sortedBy { it.id }.map { campsite ->
            val statuses =
                campsiteWindowStatuses(
                    startDate = slice.startDate,
                    endDate = slice.endDate,
                    observations = observationsByCampsite[campsite.id].orEmpty(),
                )
            CampsiteStream(
                campsiteId = campsite.id,
                statuses = statuses,
                closedForSeason = classifyStreamWindowState(statuses) == StreamWindowState.CLOSED_FOR_SEASON,
                cache = batch.cacheBlock,
            )
        }

    val closedForSeason = streams.all { it.closedForSeason }
    val dayCount = ChronoUnit.DAYS.between(slice.startDate, slice.endDate).toInt()
    return FusedWindow(
        state = if (closedForSeason) AvailabilityWindowState.CLOSED_FOR_SEASON else AvailabilityWindowState.SUCCESS,
        season = batch.seasonBlock.takeIf { closedForSeason },
        // One batch backs every campsite in a slice today, so its block is also
        // the stalest; the max keeps the rule right should the streams diverge.
        cache = streams.map { it.cache }.maxByOrNull { it.ageSeconds },
        // A closed week has no grid to draw — clients gate on `state` — so the
        // days are the wire's own dead weight.
        days =
            if (closedForSeason) {
                emptyList()
            } else {
                (0 until dayCount).map { offset ->
                    fusedDay(
                        streams = streams,
                        pollingSupported = slice.pollingSupported,
                        offset = offset,
                        date = slice.startDate.plusDays(offset.toLong()),
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
