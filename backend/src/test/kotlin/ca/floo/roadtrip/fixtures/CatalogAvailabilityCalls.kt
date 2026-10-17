package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.CatalogAvailabilityScope
import ca.floo.roadtrip.service.availability.provider.catalogAvailability
import java.time.LocalDate

/**
 * The scope-free catalog call: answers for [campsites] and asks for nothing
 * more. Most adapter tests are about classification, not about the fetch-scope
 * seam, and say so by not naming a scope; the tests that are about the seam
 * build a [CatalogAvailabilityScope] themselves.
 */
suspend fun AvailabilityProvider.catalogAvailability(
    campground: Campground,
    campsites: List<Campsite>,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch = catalogAvailability(campground, CatalogAvailabilityScope.of(campsites), startDate, endDate)
