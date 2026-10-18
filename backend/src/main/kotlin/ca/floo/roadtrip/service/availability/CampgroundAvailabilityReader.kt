package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.api.AvailabilityLoader
import ca.floo.roadtrip.service.api.FetchedObservations
import ca.floo.roadtrip.service.api.StreamWindowState
import ca.floo.roadtrip.service.api.classifyStreamWindowState
import java.time.Instant
import java.time.LocalDate

internal class CampgroundAvailabilityReader(
    private val policy: AvailabilityReadPolicy,
    private val loader: AvailabilityLoader,
    private val failoverFetcher: FailoverAvailabilityFetcher,
    private val availabilityRepo: AvailabilityRepo?,
) {
    suspend fun read(
        poiId: Long,
        campground: Campground,
        campgroundCampsites: List<Campsite>,
        campsites: List<Campsite>,
        dateContext: PoiDateContext,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant?,
    ): CampgroundWindow {
        val read = policy.read(campground, dateContext, startDate, endDate, freshAtOrAfter)
        val windows = read.windows
        val loaded =
            loader.load(
                AvailabilityLoader.Request(
                    targets = campsites.map { AvailabilityLoader.CampsiteTarget(dbId = it.id) },
                    startDate = windows.target.startDate,
                    endDate = windows.target.endDate,
                    freshAtOrAfter = read.freshAtOrAfter,
                    fetchStartDate = windows.fetch.startDate,
                    fetchEndDate = windows.fetch.endDate,
                ),
            ) {
                val result =
                    failoverFetcher.fetch(
                        providers = policy.supportingProviders(campground),
                        campground = campground,
                        campsites = campsites,
                        campgroundCampsites = campgroundCampsites,
                        window = ResolvedDateWindow(windows.fetch.startDate, windows.fetch.endDate),
                    )
                FetchedObservations(
                    observations = result.observations ?: throw availabilityProviderErrorFromAttempt(result.attempts.lastOrNull()),
                    servedBy = result.servedBy,
                )
            }
        val streams = streamsFor(campsites, windows.target, loaded.cells)
        return CampgroundWindow(
            poiId = poiId,
            campground = campground,
            window = windows.target,
            earliestDate = dateContext.earliestDate,
            latestDate = read.latestDate,
            campgroundCampsites = campgroundCampsites,
            campsites = campsites,
            streams = streams,
            provider = loaded.fetched?.servedBy ?: read.provider.id,
            campgroundRef = loaded.fetched?.observations?.campgroundRef ?: read.provider.parentRefFor(campground),
            cache = AvailabilityCacheBlock(loaded.hit, loaded.ageSeconds, loaded.ttlSeconds),
            pollingSupported = policy.pollingSupported(campground),
            reopensOnByCampsite = reopensOnByCampsite(streams, windows.target, read.freshAtOrAfter),
        )
    }

    private fun reopensOnByCampsite(
        streams: List<CampsiteStream>,
        window: ResolvedDateWindow,
        freshAtOrAfter: Instant,
    ): Map<Long, LocalDate> {
        val repo = availabilityRepo ?: return emptyMap()
        val closed =
            streams
                .filter { classifyStreamWindowState(it.statuses) == StreamWindowState.CLOSED_FOR_SEASON }
                .map { it.campsiteId }
        return repo.earliestBookableDates(closed, window.startDate, freshAtOrAfter)
    }
}
