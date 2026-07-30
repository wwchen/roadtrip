package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.PoiCampsitesAvailabilityResponseDto
import ca.floo.roadtrip.model.api.PoiCampsitesResponseSchema
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import java.time.LocalDate

// The empty-campsite branch has no provider whose capabilities could bound the
// requested window, so it validates against these fixed fallback bounds.
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
        siteTypes: List<String>,
    ): PoiCampsitesResponseSchema = catalogService.campsitesForPoi(poiId = poiId, siteTypes = siteTypes)

    /**
     * Per-campsite availability for one campground POI.
     *
     * @throws AvailabilityServiceError on unknown POI/campground or a bad date window.
     * @throws ca.floo.roadtrip.model.availability.AvailabilityProviderError on upstream failure.
     */
    suspend fun availabilityForPoi(
        poiId: Long,
        siteTypes: List<String>,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): PoiCampsitesAvailabilityResponseDto {
        val campground = campgroundRepo.findByPoi(poiId) ?: throw AvailabilityServiceError.NotFound
        val allCampsites = campsitesRepo.findByCampground(campground.id)
        val watchCaps = watchCapabilityService.capabilitiesFor(allCampsites)
        val campsites = allCampsites.filterBySiteTypes(siteTypes)
        val dateContext = dateResolver.contextForPoi(poiId)

        if (campsites.isEmpty()) {
            val window =
                dateResolver.resolveWindow(
                    startDate = startDate,
                    endDate = endDate,
                    context = dateContext,
                    bookingHorizonDays = EMPTY_WINDOW_HORIZON_DAYS,
                    maxDays = EMPTY_WINDOW_MAX_DAYS,
                    defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
                )
            return PoiCampsitesAvailabilityResponseDto(
                poiId = poiId,
                startDate = window.startDate.toString(),
                endDate = window.endDate.toString(),
                watchCapabilities = watchCaps,
                campsites = emptyList(),
            )
        }

        val result =
            availabilityService.fetchAvailability(
                campground = campground,
                campsites = campsites,
                startDate = startDate,
                endDate = endDate,
                dateContext = dateContext,
            )

        val perCampsite =
            campsites.map { campsite ->
                availabilityResponseFromObservations(
                    result.batch.copy(
                        observations = result.batch.observations.filter { it.campsiteId == campsite.id },
                        campsiteId = campsite.id,
                        startDate = result.startDate,
                        endDate = result.endDate,
                    ),
                )
            }

        return PoiCampsitesAvailabilityResponseDto(
            poiId = poiId,
            startDate = result.startDate.toString(),
            endDate = result.endDate.toString(),
            watchCapabilities = watchCaps,
            campsites = perCampsite,
        )
    }
}
