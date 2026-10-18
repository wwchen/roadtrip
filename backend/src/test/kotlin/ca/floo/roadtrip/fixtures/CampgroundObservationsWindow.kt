package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.CampgroundObservations
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.service.availability.CampgroundWindow
import ca.floo.roadtrip.service.availability.provider.testCampground
import ca.floo.roadtrip.service.availability.streamsFor
import java.time.LocalDate

internal fun CampgroundObservations.asWindow(
    provider: BookingProvider,
    campsites: List<Campsite>,
    startDate: LocalDate,
    endDate: LocalDate,
): CampgroundWindow {
    val window = ResolvedDateWindow(startDate, endDate)
    return CampgroundWindow(
        poiId = 1L,
        campground = testCampground(bookingProvider = provider.id, bookingProviderRef = campgroundRef?.serialize()),
        window = window,
        earliestDate = startDate,
        latestDate = endDate,
        campgroundCampsites = campsites,
        campsites = campsites,
        streams = streamsFor(campsites, window, observations),
        provider = provider,
        campgroundRef = campgroundRef,
        cache = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        pollingSupported = true,
        reopensOnByCampsite = emptyMap(),
    )
}
