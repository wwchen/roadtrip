package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.LocalDate

/**
 * The serving provider of a campground and what it can promise, for callers
 * that need those answers without fetching availability: the POI detail's
 * `latest_date`, the availability read path's own horizon, and whether a watch
 * over the campground could ever be polled. Null (or false) when no registered
 * provider claims the campground — the caller has no ceiling to publish rather
 * than a wrong one.
 */
internal class BookingHorizonResolver(
    private val availabilityProviders: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
) {
    /** The first registered provider that claims [campground], or null. */
    fun servingProvider(campground: Campground): AvailabilityProvider? =
        availabilityProviders.firstOrNull { it.supportsCampground(campground) }

    /**
     * Whether the serving provider can be polled for openings at all — one pick
     * per campground, which is the granularity the read paths fold into a cell's
     * `watchable`.
     */
    fun internalPollingSupported(campground: Campground): Boolean =
        servingProvider(campground)?.capabilities?.supportsInternalPolling == true

    fun horizonDaysFor(campground: Campground): Int? = servingProvider(campground)?.capabilities?.bookingHorizonDays

    fun latestDate(
        campground: Campground,
        context: PoiDateContext,
    ): LocalDate? = horizonDaysFor(campground)?.let { dateResolver.latestDate(context, it) }
}
