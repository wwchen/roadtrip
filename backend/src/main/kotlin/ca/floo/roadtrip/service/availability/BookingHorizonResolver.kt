package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.LocalDate

/**
 * How far ahead a campground's serving provider can be asked about, for callers
 * that need the ceiling without fetching availability: the POI detail's
 * `latest_date` and the availability read path's own horizon. Null when no
 * registered provider claims the campground — the caller has no ceiling to
 * publish rather than a wrong one.
 */
internal class BookingHorizonResolver(
    private val availabilityProviders: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
) {
    fun horizonDaysFor(campground: Campground): Int? =
        availabilityProviders.firstOrNull { it.supportsCampground(campground) }?.capabilities?.bookingHorizonDays

    fun latestDate(
        campground: Campground,
        context: PoiDateContext,
    ): LocalDate? = horizonDaysFor(campground)?.let { dateResolver.latestDate(context, it) }
}
