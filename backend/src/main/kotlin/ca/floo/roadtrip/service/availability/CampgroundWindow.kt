package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.api.StreamWindowState
import ca.floo.roadtrip.service.api.availabilityResponseDto
import ca.floo.roadtrip.service.api.classifyObservedWindowState
import ca.floo.roadtrip.service.api.dayClassificationFromCampsiteStatuses
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class CampgroundWindow(
    val poiId: Long,
    val campground: Campground,
    val window: ResolvedDateWindow,
    val earliestDate: LocalDate,
    val latestDate: LocalDate?,
    val campgroundCampsites: List<Campsite>,
    val campsites: List<Campsite>,
    val streams: List<CampsiteStream>,
    val provider: BookingProvider?,
    val campgroundRef: BookingProviderRef?,
    val cache: AvailabilityCacheBlock?,
    val pollingSupported: Boolean,
    val reopensOnByCampsite: Map<Long, LocalDate>,
)

internal data class CampsiteEnvelope(
    val response: AvailabilityResponseDto,
    val stream: CampsiteStream,
)

internal fun CampgroundWindow.narrowedTo(narrowed: List<Campsite>): CampgroundWindow {
    val kept = narrowed.mapTo(mutableSetOf()) { it.id }
    return copy(campsites = narrowed, streams = streams.filter { it.campsiteId in kept })
}

internal fun CampgroundWindow.checkedSites(): Int = campsites.map { it.id }.distinct().size

internal fun CampgroundWindow.reopensOn(): AvailabilitySeasonBlock? =
    campsites
        .mapNotNull { reopensOnByCampsite[it.id] }
        .minOrNull()
        ?.let { AvailabilitySeasonBlock(reopensOn = it.toString()) }

internal fun CampgroundWindow.fusedWindow(): FusedWindow =
    fuseWindow(
        streams = streams,
        window = window,
        earliestDate = earliestDate,
        pollingSupported = pollingSupported,
        cache = cache,
        season = reopensOn(),
    )

internal fun CampgroundWindow.perCampsiteEnvelopes(): List<CampsiteEnvelope> {
    val provider = provider ?: return emptyList()
    val cache = cache ?: return emptyList()
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
                        scopeRef = campgroundRef?.serialize(),
                        campsiteId = stream.campsiteId,
                    ),
                stream = stream,
            )
        }
}
