package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.config.ApiCacheConfig
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
import ca.floo.roadtrip.service.api.StreamWindowState
import ca.floo.roadtrip.service.api.campsiteWindowStatuses
import ca.floo.roadtrip.service.api.classifyStreamWindowState
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private const val DEFAULT_AVAILABILITY_DAYS: Int = 7

internal data class CampsiteAvailabilityResult(
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** The serving provider's booking horizon, for the client's date picker. */
    val latestDate: LocalDate,
    val batch: AvailabilityObservationBatch,
    /**
     * Per campsite of this read, the earliest stored date it is bookable — the
     * material a "closed for the season, reopening on X" answer is reduced from.
     * Read from the table, so it is the same on a cache hit as on a fetch.
     */
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

    /**
     * One campground's availability, as the base-unit facts for [campsites].
     *
     * [campgroundCampsites] is the campground's whole campsite set and is what
     * the provider port is given: the one upstream call covers the campground, so
     * every campsite's cells are stored, and the next read of any of them is
     * served without calling the vendor. [campsites] is this read's own
     * narrowing — the cells it answers from and the campsites its season block is
     * reduced over — and it goes no further down than here.
     *
     * Both are required: defaulting [campgroundCampsites] to [campsites] would
     * let a caller quietly ask for one campground's worth of cells per filter,
     * which is the refetch this seam exists to stop.
     */
    suspend fun fetchAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        campgroundCampsites: List<Campsite>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        dateContext: PoiDateContext,
        freshAtOrAfter: Instant? = null,
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
        // One cutoff for the whole read: what the store may answer with, and what
        // counts as evidence of a reopening.
        val freshAt = freshAtOrAfter ?: Instant.now(clock).minus(snapshotFreshnessTtl(provider))

        val batch =
            availabilityLoader.loadOrFetch(
                AvailabilityLoader.Request(
                    metadata =
                        AvailabilityLoader.Metadata(
                            provider = provider.id.id,
                            scope = provider.parentRefFor(campground),
                        ),
                    targets = campsites.map { AvailabilityLoader.CampsiteTarget(dbId = it.id) },
                    startDate = windows.target.startDate,
                    endDate = windows.target.endDate,
                    freshAtOrAfter = freshAt,
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
                result.batch ?: throw availabilityProviderErrorFromAttempt(result.attempts.lastOrNull())
            }

        return CampsiteAvailabilityResult(
            startDate = windows.target.startDate,
            endDate = windows.target.endDate,
            // providerFor above already found a provider for this campground, so the same pick here can't miss.
            latestDate = bookingHorizons.latestDate(campground, dateContext)!!,
            batch = batch,
            reopensOnByCampsite = reopensOnByCampsite(campsites, batch, windows.target, freshAt),
        )
    }

    /**
     * Looks up a reopen date only for the campsites that could show one: the ones
     * whose window is closed for the season, by the same classification the
     * response gates the season block on.
     *
     * The narrower gate is the point. "Nothing bookable in the window" also
     * describes a campground that is merely full, which is most of them on a stay
     * check — and `staySummary` never reads a reopen date, so every one of those
     * lookups would be a round trip per POI for an answer nobody surfaces, under
     * a connection pool the fan-out already shares. A campsite that *is* shut may
     * still reopen inside a stored cell beyond the window, which is why this
     * reads past it.
     */
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

    private fun providerFor(campground: Campground): AvailabilityProvider =
        bookingHorizons.servingProvider(campground) ?: throw AvailabilityServiceError.UnknownCampground

    /**
     * The serving provider's booking horizon for [campground], or null when no
     * provider claims it. Non-throwing counterpart to [providerFor], for
     * callers (the empty-campsite branch) that have a fallback of their own.
     */
    fun bookingHorizonDaysFor(campground: Campground): Int? = bookingHorizons.horizonDaysFor(campground)

    /**
     * Whether the campground's serving provider can be polled for openings —
     * one pick for the whole slice, not one per campsite.
     */
    fun internalPollingSupportedFor(campground: Campground): Boolean = bookingHorizons.internalPollingSupported(campground)
}

internal fun defaultSnapshotFreshnessTtl(providerId: BookingProvider): Duration = ApiCacheEntity.availability(providerId).defaultTtl

internal fun configuredSnapshotFreshnessTtl(cache: ApiCacheConfig): (AvailabilityProvider) -> Duration =
    { provider -> cache.availabilityTtl(provider.id) }
