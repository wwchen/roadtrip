package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.PoiCampsitesAvailabilityResponseDto
import ca.floo.roadtrip.model.api.PoiCampsitesResponseSchema
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.api.seasonElement
import java.time.Instant
import java.time.LocalDate

// The empty-campsite branch still tries to resolve the campground's serving
// provider for its real booking horizon; these are the fallback bounds for
// when no provider claims the campground.
internal const val EMPTY_WINDOW_DEFAULT_DAYS = 7
internal const val EMPTY_WINDOW_MAX_DAYS = 60
internal const val EMPTY_WINDOW_HORIZON_DAYS = 365

/**
 * Use-case orchestration for the POI campsite read path: the campsite catalog
 * listing and the per-campsite availability envelope. Routes stay an HTTP
 * shell — they parse params, call this controller, and map
 * [AvailabilityServiceError] / provider errors to status codes.
 */
internal class CampsiteAvailabilityController(
    private val campgroundRepo: CampgroundRepo,
    private val campsitesRepo: CampsiteRepo,
    private val catalogService: CampsiteCatalogService,
    private val policy: AvailabilityReadPolicy,
    private val reader: CampgroundAvailabilityReader,
    private val dateResolver: AvailabilityDateResolver,
    private val watchCapabilityService: WatchCapabilityService,
) {
    /** @throws AvailabilityServiceError.NotFound when the POI has no campground. */
    fun campsitesForPoi(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
    ): PoiCampsitesResponseSchema = catalogService.campsitesForPoi(poiId = poiId, siteTypes = siteTypes)

    /**
     * One POI's resolved availability, shared by the detail endpoint and the
     * bulk endpoint.
     *
     * @throws AvailabilityServiceError on unknown POI/campground or a bad date window.
     * @throws ca.floo.roadtrip.model.availability.AvailabilityProviderError on upstream failure.
     */
    suspend fun campgroundWindow(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant? = null,
    ): CampgroundWindow {
        val campground = campgroundRepo.findByPoi(poiId) ?: throw AvailabilityServiceError.NotFound
        val allCampsites = campsitesRepo.findByCampground(campground.id)
        val campsites = allCampsites.filterBySiteTypes(siteTypes)
        val dateContext = dateResolver.contextForPoi(poiId)

        if (campsites.isEmpty()) {
            return emptyWindow(poiId, campground, allCampsites, dateContext, startDate, endDate)
        }

        return reader.read(
            poiId = poiId,
            campground = campground,
            campgroundCampsites = allCampsites,
            campsites = campsites,
            dateContext = dateContext,
            startDate = startDate,
            endDate = endDate,
            freshAtOrAfter = freshAtOrAfter,
        )
    }

    private fun emptyWindow(
        poiId: Long,
        campground: Campground,
        allCampsites: List<Campsite>,
        dateContext: PoiDateContext,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): CampgroundWindow.NoCampsitesMatched {
        val horizonDays = policy.bookingHorizonDays(campground)
        val window =
            dateResolver.resolveWindow(
                startDate = startDate,
                endDate = endDate,
                context = dateContext,
                bookingHorizonDays = horizonDays ?: EMPTY_WINDOW_HORIZON_DAYS,
                maxDays = EMPTY_WINDOW_MAX_DAYS,
                defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
            )
        return CampgroundWindow.NoCampsitesMatched(
            poiId = poiId,
            campground = campground,
            window = window,
            earliestDate = dateContext.earliestDate,
            latestDate = horizonDays?.let { dateResolver.latestDate(dateContext, it) },
            campgroundCampsites = allCampsites,
        )
    }

    /**
     * The fused availability week for one campground POI.
     *
     * @throws AvailabilityServiceError on unknown POI/campground or a bad date window.
     * @throws ca.floo.roadtrip.model.availability.AvailabilityProviderError on upstream failure.
     */
    suspend fun availabilityForPoi(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        // Null for anonymous and magic-link readers. Capability gating narrows
        // to what *this* caller could set, so `atc` is simply absent for them.
        requester: UserId? = null,
    ): PoiCampsitesAvailabilityResponseDto {
        val readWindow = campgroundWindow(poiId, siteTypes, startDate, endDate)
        val watchCaps = watchCapabilityService.capabilitiesFor(readWindow.campgroundCampsites, requester)
        val fused = readWindow.fusedWindow()

        return PoiCampsitesAvailabilityResponseDto(
            poiId = poiId,
            startDate = readWindow.window.startDate.toString(),
            endDate = readWindow.window.endDate.toString(),
            latestDate = readWindow.latestDate?.toString(),
            state = fused.state,
            season = seasonElement(fused.season),
            cache = fused.cache,
            days = fused.days,
            watchCapabilities = watchCaps,
        )
    }
}
