package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.api.AvailabilityLoader
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.Duration
import java.time.LocalDate

private const val DEFAULT_AVAILABILITY_DAYS: Int = 7

internal data class CampsiteAvailabilityResult(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val batch: AvailabilityObservationBatch,
)

internal class CampsiteAvailabilityService(
    private val availabilityProviders: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
    private val failoverFetcher: FailoverAvailabilityFetcher,
    availabilityRepo: AvailabilityRepo? = null,
    private val snapshotFreshnessTtl: (provider: AvailabilityProvider) -> Duration = { defaultSnapshotFreshnessTtl(it.id) },
) {
    private val availabilityLoader = AvailabilityLoader(availabilityRepo)

    suspend fun fetchAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        dateContext: PoiDateContext,
    ): CampsiteAvailabilityResult {
        val provider = providerFor(campground)
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
        val windows = AvailabilityWindows(target = targetWindow, fetch = fetchWindow)

        val supportingProviders = availabilityProviders.filter { it.supportsCampground(campground) }

        val batch =
            availabilityLoader.loadOrFetch(
                AvailabilityLoader.Request(
                    metadata = AvailabilityLoader.Metadata(provider = provider.id.id),
                    targets = campsites.map { AvailabilityLoader.CampsiteTarget(dbId = it.id) },
                    startDate = windows.target.startDate,
                    endDate = windows.target.endDate,
                    ttl = snapshotFreshnessTtl(provider),
                ),
            ) {
                val result =
                    failoverFetcher.fetch(
                        providers = supportingProviders,
                        campground = campground,
                        campsites = campsites,
                        window = ResolvedDateWindow(windows.fetch.startDate, windows.fetch.endDate),
                    )
                result.batch ?: throw availabilityProviderErrorFromAttempt(result.attempts.lastOrNull())
            }

        return CampsiteAvailabilityResult(
            startDate = windows.target.startDate,
            endDate = windows.target.endDate,
            batch = batch,
        )
    }

    private fun providerFor(campground: Campground): AvailabilityProvider =
        availabilityProviders.firstOrNull { it.supportsCampground(campground) }
            ?: throw AvailabilityServiceError.UnknownCampground
}

internal fun defaultSnapshotFreshnessTtl(providerId: BookingProvider): Duration =
    when (providerId) {
        BookingProvider.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
        BookingProvider.CAMPFLARE -> ApiCacheEntity.CAMPFLARE_AVAILABILITY.defaultTtl
        BookingProvider.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl
        BookingProvider.RESERVEAMERICA -> ApiCacheEntity.RESERVEAMERICA_AVAILABILITY.defaultTtl
        BookingProvider.RESERVECALIFORNIA -> ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY.defaultTtl
    }
