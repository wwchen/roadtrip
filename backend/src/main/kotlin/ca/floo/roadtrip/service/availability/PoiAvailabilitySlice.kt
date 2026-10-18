package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import java.time.LocalDate

/**
 * One campsite's shaped availability, plus the observations it was built from
 * so a caller can derive more (run lengths) without re-filtering the batch.
 */
internal data class CampsiteEnvelope(
    val response: AvailabilityResponseDto,
    val observations: List<CampsiteDayObservation>,
)

/**
 * One POI's resolved availability read, before it is shaped for a specific
 * endpoint. [batch] is null when the POI has no campsites matching the
 * requested site types, in which case only the window is meaningful.
 */
internal data class PoiAvailabilitySlice(
    val poiId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** The first bookable date in the POI's own time zone. */
    val earliestDate: LocalDate,
    /**
     * [earliestDate] plus the serving provider's booking horizon, or null when
     * no provider claims the campground and there is no horizon to state.
     */
    val latestDate: LocalDate?,
    val allCampsites: List<Campsite>,
    val campsites: List<Campsite>,
    val batch: AvailabilityObservationBatch?,
    val reopensOnByCampsite: Map<Long, LocalDate>,
    /**
     * Whether the campground's serving provider can be polled for openings. A
     * property of that one provider, so it is picked once per slice and folded
     * into every cell's `watchable` from here.
     */
    val pollingSupported: Boolean,
)

internal fun PoiAvailabilitySlice.reopensOn(): AvailabilitySeasonBlock? =
    campsites
        .mapNotNull { reopensOnByCampsite[it.id] }
        .minOrNull()
        ?.let { AvailabilitySeasonBlock(reopensOn = it.toString()) }

internal fun PoiAvailabilitySlice.window(): ResolvedDateWindow = ResolvedDateWindow(startDate, endDate)

internal fun PoiAvailabilitySlice.streams(): List<CampsiteStream> =
    batch?.let { streamsFor(campsites, window(), it.observations) }.orEmpty()

internal fun PoiAvailabilitySlice.fusedWindow(): FusedWindow =
    fuseWindow(
        streams = streams(),
        window = window(),
        earliestDate = earliestDate,
        pollingSupported = pollingSupported,
        cache = batch?.cacheBlock,
        season = reopensOn(),
    )

/**
 * One envelope per campsite, each narrowed to that campsite's observations.
 * Both read endpoints shape their response from this, so a change to the
 * envelope lands in one place. Empty when no campsite matched the filter.
 */
internal fun PoiAvailabilitySlice.perCampsiteEnvelopes(): List<CampsiteEnvelope> {
    val batch = batch ?: return emptyList()
    return campsites.map { campsite ->
        val forCampsite = batch.observations.filter { it.campsiteId == campsite.id }
        CampsiteEnvelope(
            response =
                availabilityResponseFromObservations(
                    batch =
                        batch.copy(
                            observations = forCampsite,
                            campsiteId = campsite.id,
                            startDate = startDate,
                            endDate = endDate,
                        ),
                    pollingSupported = pollingSupported,
                    earliestDate = earliestDate,
                    seasonBlock =
                        reopensOnByCampsite[campsite.id]?.let { AvailabilitySeasonBlock(reopensOn = it.toString()) },
                ),
            observations = forCampsite,
        )
    }
}
