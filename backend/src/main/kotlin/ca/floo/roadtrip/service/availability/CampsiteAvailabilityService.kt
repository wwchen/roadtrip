package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheConfig
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.api.AvailabilityLoader
import ca.floo.roadtrip.service.api.StreamWindowState
import ca.floo.roadtrip.service.api.campsiteWindowStatuses
import ca.floo.roadtrip.service.api.classifyStreamWindowState
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

internal data class CampsiteAvailabilityResult(
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** The serving provider's booking horizon, for the client's date picker. */
    val latestDate: LocalDate,
    val batch: AvailabilityObservationBatch,
    val reopensOnByCampsite: Map<Long, LocalDate>,
)

internal class CampsiteAvailabilityService(
    private val availabilityProviders: List<AvailabilityProvider>,
    private val dateResolver: AvailabilityDateResolver,
    private val failoverFetcher: FailoverAvailabilityFetcher,
    private val bookingHorizons: BookingHorizonResolver,
    private val availabilityRepo: AvailabilityRepo? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val snapshotFreshnessTtl: (provider: AvailabilityProvider) -> Duration = { defaultSnapshotFreshnessTtl(it.id) },
) {
    private val availabilityLoader = AvailabilityLoader(availabilityRepo, clock)
    private val readPolicy = AvailabilityReadPolicy(availabilityProviders, dateResolver, bookingHorizons, clock, snapshotFreshnessTtl)

    suspend fun fetchAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        campgroundCampsites: List<Campsite>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        dateContext: PoiDateContext,
        freshAtOrAfter: Instant? = null,
    ): CampsiteAvailabilityResult {
        val read =
            readPolicy.read(
                campground = campground,
                dateContext = dateContext,
                startDate = startDate,
                endDate = endDate,
                freshAtOrAfter = freshAtOrAfter,
            )
        val provider = read.provider
        val windows = read.windows
        val freshAt = read.freshAtOrAfter
        val supportingProviders = readPolicy.supportingProviders(campground)

        val requestScope = provider.parentRefFor(campground)
        var servedBy: BookingProvider? = null
        var fetchedScope: BookingProviderRef? = null
        val loaded =
            availabilityLoader.load(
                AvailabilityLoader.Request(
                    targets = campsites.map { AvailabilityLoader.CampsiteTarget(dbId = it.id) },
                    startDate = windows.target.startDate,
                    endDate = windows.target.endDate,
                    freshAtOrAfter = freshAt,
                    fetchStartDate = windows.fetch.startDate,
                    fetchEndDate = windows.fetch.endDate,
                ),
            ) {
                val result =
                    failoverFetcher.fetch(
                        providers = supportingProviders,
                        campground = campground,
                        campsites = campsites,
                        campgroundCampsites = campgroundCampsites,
                        window = ResolvedDateWindow(windows.fetch.startDate, windows.fetch.endDate),
                    )
                servedBy = result.servedBy
                val fetched = result.observations ?: throw availabilityProviderErrorFromAttempt(result.attempts.lastOrNull())
                fetchedScope = fetched.campgroundRef
                fetched
            }
        val batch =
            AvailabilityObservationBatch(
                provider = (servedBy ?: provider.id).id,
                startDate = windows.target.startDate,
                endDate = windows.target.endDate,
                observations = loaded.cells.within(campsites.mapTo(mutableSetOf()) { it.id }, windows.target),
                cacheBlock = AvailabilityCacheBlock(loaded.hit, loaded.ageSeconds, loaded.ttlSeconds),
                scope = fetchedScope ?: requestScope,
            )

        return CampsiteAvailabilityResult(
            startDate = windows.target.startDate,
            endDate = windows.target.endDate,
            latestDate = read.latestDate ?: throw AvailabilityServiceError.UnknownCampground,
            batch = batch,
            reopensOnByCampsite = reopensOnByCampsite(campsites, batch, windows.target, freshAt),
        )
    }

    private fun reopensOnByCampsite(
        campsites: List<Campsite>,
        batch: AvailabilityObservationBatch,
        window: ResolvedDateWindow,
        freshAtOrAfter: Instant,
    ): Map<Long, LocalDate> {
        val repo = availabilityRepo ?: return emptyMap()
        val observationsByCampsite = batch.observations.groupBy { it.campsiteId }
        val closed =
            campsites
                .filter { campsite ->
                    val statuses =
                        campsiteWindowStatuses(
                            startDate = window.startDate,
                            endDate = window.endDate,
                            observations = observationsByCampsite[campsite.id].orEmpty(),
                        )
                    classifyStreamWindowState(statuses) == StreamWindowState.CLOSED_FOR_SEASON
                }.map { it.id }
        return repo.earliestBookableDates(closed, window.startDate, freshAtOrAfter)
    }

    /**
     * The serving provider's booking horizon for [campground], or null when no
     * provider claims it. Non-throwing counterpart to [AvailabilityReadPolicy.read], for
     * callers (the empty-campsite branch) that have a fallback of their own.
     */
    fun bookingHorizonDaysFor(campground: Campground): Int? = readPolicy.bookingHorizonDays(campground)

    /**
     * Whether the campground's serving provider can be polled for openings —
     * one pick for the whole slice, not one per campsite.
     */
    fun internalPollingSupportedFor(campground: Campground): Boolean = readPolicy.pollingSupported(campground)
}

private fun List<CampsiteDayObservation>.within(
    campsiteIds: Set<Long>,
    window: ResolvedDateWindow,
): List<CampsiteDayObservation> =
    filter {
        !it.date.isBefore(window.startDate) &&
            it.date.isBefore(window.endDate) &&
            (campsiteIds.isEmpty() || it.campsiteId in campsiteIds)
    }

internal fun defaultSnapshotFreshnessTtl(providerId: BookingProvider): Duration = ApiCacheEntity.availability(providerId).defaultTtl

internal fun configuredSnapshotFreshnessTtl(cache: ApiCacheConfig): (AvailabilityProvider) -> Duration =
    { provider -> cache.availabilityTtl(provider.id) }
