package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.PoiCampsitesAvailabilityResponseDto
import ca.floo.roadtrip.model.api.PoiCampsitesResponseSchema
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
 * Use-case orchestration for the POI campsite read slice: the campsite catalog
 * listing and the per-campsite availability envelope. Routes stay an HTTP
 * shell — they parse params, call this controller, and map
 * [AvailabilityServiceError] / provider errors to status codes.
 */
internal class CampsiteAvailabilityController(
    private val campgroundRepo: CampgroundRepo,
    private val campsitesRepo: CampsiteRepo,
    private val catalogService: CampsiteCatalogService,
    private val availabilityService: CampsiteAvailabilityService,
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
    suspend fun poiAvailabilitySlice(
        poiId: Long,
        siteTypes: List<CampsiteKind>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        freshAtOrAfter: Instant? = null,
    ): PoiAvailabilitySlice {
        val campground = campgroundRepo.findByPoi(poiId) ?: throw AvailabilityServiceError.NotFound
        val allCampsites = campsitesRepo.findByCampground(campground.id)
        val campsites = allCampsites.filterBySiteTypes(siteTypes)
        val dateContext = dateResolver.contextForPoi(poiId)

        if (campsites.isEmpty()) {
            // The site_type filter dropped every campsite, but the campground
            // may still have a serving provider — ask it for the real horizon
            // before falling back to the flat default.
            val horizonDays = availabilityService.bookingHorizonDaysFor(campground) ?: EMPTY_WINDOW_HORIZON_DAYS
            val window =
                dateResolver.resolveWindow(
                    startDate = startDate,
                    endDate = endDate,
                    context = dateContext,
                    bookingHorizonDays = horizonDays,
                    maxDays = EMPTY_WINDOW_MAX_DAYS,
                    defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
                )
            return PoiAvailabilitySlice(
                poiId = poiId,
                startDate = window.startDate,
                endDate = window.endDate,
                earliestDate = dateContext.earliestDate,
                latestDate = dateResolver.latestDate(dateContext, horizonDays),
                allCampsites = allCampsites,
                campsites = emptyList(),
                batch = null,
            )
        }

        val result =
            availabilityService.fetchAvailability(
                campground = campground,
                campsites = campsites,
                startDate = startDate,
                endDate = endDate,
                dateContext = dateContext,
                freshAtOrAfter = freshAtOrAfter,
            )

        return PoiAvailabilitySlice(
            poiId = poiId,
            startDate = result.startDate,
            endDate = result.endDate,
            earliestDate = dateContext.earliestDate,
            latestDate = result.latestDate,
            allCampsites = allCampsites,
            campsites = campsites,
            batch = result.batch,
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
        val slice = poiAvailabilitySlice(poiId, siteTypes, startDate, endDate)
        val watchCaps = watchCapabilityService.capabilitiesFor(slice.allCampsites, requester)
        val fused = fuse(slice, watchCapabilityService::pollingSupported, slice.earliestDate)

        return PoiCampsitesAvailabilityResponseDto(
            poiId = poiId,
            startDate = slice.startDate.toString(),
            endDate = slice.endDate.toString(),
            latestDate = slice.latestDate.toString(),
            state = fused.state,
            season = seasonElement(fused.season),
            cache = fused.cache,
            days = fused.days,
            watchCapabilities = watchCaps,
        )
    }
}
