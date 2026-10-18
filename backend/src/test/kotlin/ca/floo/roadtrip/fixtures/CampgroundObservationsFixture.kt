package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.CampgroundObservations
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import java.time.LocalDate

fun CampgroundObservations.askedFor(
    provider: BookingProvider,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch =
    AvailabilityObservationBatch(
        provider = provider.id,
        startDate = startDate,
        endDate = endDate,
        observations = observations,
        cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        scope = campgroundRef,
    )
