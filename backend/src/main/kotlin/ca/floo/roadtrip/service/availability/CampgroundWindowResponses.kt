package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWindowState
import ca.floo.roadtrip.model.api.BulkPoiSummaryDto
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.availabilityResponseDto
import ca.floo.roadtrip.service.api.dayClassificationFromCampsiteStatuses
import java.time.temporal.ChronoUnit

internal fun CampgroundWindow.narrowedToGroup(groupSize: Int?): CampgroundWindow =
    when (this) {
        is CampgroundWindow.Answered -> narrowedTo(campsites.fittingGroup(groupSize))
        is CampgroundWindow.NoCampsitesMatched -> this
    }

internal fun CampgroundWindow.Answered.narrowedTo(narrowed: List<Campsite>): CampgroundWindow.Answered {
    val kept = narrowed.mapTo(mutableSetOf()) { it.id }
    return copy(campsites = narrowed, streams = streams.filter { it.campsiteId in kept })
}

internal fun CampgroundWindow.checkedSites(): Int =
    when (this) {
        is CampgroundWindow.Answered -> campsites.map { it.id }.distinct().size
        is CampgroundWindow.NoCampsitesMatched -> 0
    }

internal fun CampgroundWindow.staySummary(): BulkPoiSummaryDto =
    when (this) {
        is CampgroundWindow.Answered -> staySummaryOf(streams, checkedSites(), cache)
        is CampgroundWindow.NoCampsitesMatched -> staySummaryOf(emptyList(), 0, null)
    }

internal fun CampgroundWindow.fusedWindow(): FusedWindow =
    when (this) {
        is CampgroundWindow.Answered ->
            fuseWindow(
                streams = streams,
                window = window,
                earliestDate = earliestDate,
                pollingSupported = pollingSupported,
                cache = cache,
                season = reopensOn(),
            )
        is CampgroundWindow.NoCampsitesMatched ->
            FusedWindow(AvailabilityWindowState.EMPTY, season = null, cache = null, days = emptyList())
    }

internal fun CampgroundWindow.perCampsiteEnvelopes(): List<CampsiteEnvelope> =
    when (this) {
        is CampgroundWindow.Answered -> campsiteEnvelopes()
        is CampgroundWindow.NoCampsitesMatched -> emptyList()
    }

internal fun CampgroundWindow.Answered.reopensOn(): AvailabilitySeasonBlock? =
    campsites
        .mapNotNull { reopensOnByCampsite[it.id] }
        .minOrNull()
        ?.let { AvailabilitySeasonBlock(reopensOn = it.toString()) }

private fun CampgroundWindow.Answered.campsiteEnvelopes(): List<CampsiteEnvelope> {
    val streamsById = streams.associateBy { it.campsiteId }
    val dayCount = ChronoUnit.DAYS.between(window.startDate, window.endDate).toInt()
    return campsites
        .map { it.id }
        .distinct()
        .mapNotNull { streamsById[it] }
        .map { stream ->
            val state = classifyObservedWindowState(stream.statuses, stream.observed)
            CampsiteEnvelope(
                response =
                    availabilityResponseDto(
                        provider = provider.id,
                        startDate = window.startDate,
                        endDate = window.endDate,
                        perDay =
                            (0 until dayCount).map { offset ->
                                dayClassificationFromCampsiteStatuses(
                                    date = window.startDate.plusDays(offset.toLong()).toString(),
                                    statuses =
                                        if (stream.observed[offset]) {
                                            mapOf(stream.campsiteId to stream.statuses[offset])
                                        } else {
                                            emptyMap()
                                        },
                                )
                            },
                        state = state,
                        seasonBlock =
                            reopensOnByCampsite[stream.campsiteId]
                                ?.let { AvailabilitySeasonBlock(reopensOn = it.toString()) }
                                .takeIf { state == StreamWindowState.CLOSED_FOR_SEASON },
                        cacheBlock = cache,
                        pollingSupported = pollingSupported,
                        earliestDate = earliestDate,
                        campgroundRef = campgroundRef?.serialize(),
                        campsiteId = stream.campsiteId,
                    ),
                stream = stream,
            )
        }
}
