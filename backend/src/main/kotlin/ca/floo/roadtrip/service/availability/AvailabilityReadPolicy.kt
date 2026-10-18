package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheConfig
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private const val DEFAULT_AVAILABILITY_DAYS: Int = 7

internal data class AvailabilityRead(
    val provider: AvailabilityProvider,
    val windows: AvailabilityWindows,
    val freshAtOrAfter: Instant,
    val latestDate: LocalDate?,
)

internal class AvailabilityReadPolicy(
    private val providers: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
    private val bookingHorizons: BookingHorizonResolver,
    private val clock: Clock,
    private val snapshotFreshnessTtl: (AvailabilityProvider) -> Duration,
) {
    fun read(
        campground: Campground,
        dateContext: PoiDateContext,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant?,
    ): AvailabilityRead {
        val provider = servingProvider(campground) ?: throw AvailabilityServiceError.UnknownCampground
        val caps = provider.capabilities
        val targetWindow =
            dateResolver.resolveWindow(
                startDate = startDate,
                endDate = endDate,
                context = dateContext,
                bookingHorizonDays = caps.bookingHorizonDays,
                maxDays = caps.maxPollWindowDays,
                defaultDays = DEFAULT_AVAILABILITY_DAYS,
            )
        val fetchWindow =
            dateResolver.wideWindow(
                anchor = targetWindow.startDate,
                context = dateContext,
                maxPollWindowDays = caps.maxPollWindowDays,
                bookingHorizonDays = caps.bookingHorizonDays,
            ) ?: targetWindow
        return AvailabilityRead(
            provider = provider,
            windows = AvailabilityWindows(target = targetWindow, fetch = fetchWindow),
            freshAtOrAfter = freshAtOrAfter ?: Instant.now(clock).minus(snapshotFreshnessTtl(provider)),
            latestDate = latestDate(campground, dateContext),
        )
    }

    fun servingProvider(campground: Campground): AvailabilityProvider? = bookingHorizons.servingProvider(campground)

    fun latestDate(
        campground: Campground,
        dateContext: PoiDateContext,
    ): LocalDate? = bookingHorizons.latestDate(campground, dateContext)

    fun bookingHorizonDays(campground: Campground): Int? = bookingHorizons.horizonDaysFor(campground)

    fun supportingProviders(campground: Campground): List<AvailabilityProvider> = providers.filter { it.supportsCampground(campground) }

    fun pollingSupported(campground: Campground): Boolean = bookingHorizons.internalPollingSupported(campground)
}

internal fun defaultSnapshotFreshnessTtl(providerId: BookingProvider): Duration = ApiCacheEntity.availability(providerId).defaultTtl

internal fun configuredSnapshotFreshnessTtl(cache: ApiCacheConfig): (AvailabilityProvider) -> Duration =
    { provider -> cache.availabilityTtl(provider.id) }
