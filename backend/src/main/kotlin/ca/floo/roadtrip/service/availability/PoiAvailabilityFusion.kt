package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityCellDto
import ca.floo.roadtrip.model.api.AvailabilityDayDto
import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.DayClassification
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.classifyWindowState
import ca.floo.roadtrip.service.api.dayClassificationsFromObservations
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
 * One campsite's window, narrowed from the slice's batch: a status per date,
 * whether the stream is closed for the season, and whether a watch on this
 * campsite could ever be polled.
 */
internal class CampsiteStream(
    val campsiteId: Long,
    val polls: Boolean,
    val days: List<DayClassification>,
    val closedForSeason: Boolean,
    val cache: AvailabilityCacheBlock,
)

/**
 * Fuses one POI's campsite streams into the campground week the wire carries:
 * the day's rollup over every campsite, one cell per campsite, and the
 * watchability of both. Pure — [pollingSupported] is the only lookup and the
 * caller owns it, so this stays testable without a database.
 */
internal fun fuse(
    slice: PoiAvailabilitySlice,
    pollingSupported: (Campsite) -> Boolean,
    earliestDate: LocalDate,
): FusedWindow {
    val batch = slice.batch
    if (batch == null || slice.campsites.isEmpty()) {
        return FusedWindow(AvailabilityWindowState.EMPTY, season = null, cache = null, days = emptyList())
    }

    val streams =
        slice.campsites.sortedBy { it.id }.map { campsite ->
            val days =
                dayClassificationsFromObservations(
                    startDate = slice.startDate,
                    endDate = slice.endDate,
                    observations = batch.observations.filter { it.campsiteId == campsite.id },
                )
            CampsiteStream(
                campsiteId = campsite.id,
                polls = pollingSupported(campsite),
                days = days,
                closedForSeason = classifyWindowState(days) == AvailabilityWindowState.CLOSED_FOR_SEASON.wireValue,
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
        days =
            (0 until dayCount).map { offset ->
                val date = slice.startDate.plusDays(offset.toLong())
                fusedDay(streams, offset, date, bookable = !date.isBefore(earliestDate))
            },
    )
}

/** One date across every stream: cells in ascending campsite id, then the rollup. */
private fun fusedDay(
    streams: List<CampsiteStream>,
    offset: Int,
    date: LocalDate,
    bookable: Boolean,
): AvailabilityDayDto {
    val cells =
        streams.associate { stream ->
            val status = stream.days[offset].status
            stream.campsiteId to
                AvailabilityCellDto(
                    status = status,
                    watchable = status.watchable && stream.polls && bookable,
                )
        }
    return AvailabilityDayDto(
        date = date.toString(),
        status = rollupStatus(cells.values.map { it.status }),
        watchable = cells.values.any { it.watchable },
        cells = cells,
    )
}
