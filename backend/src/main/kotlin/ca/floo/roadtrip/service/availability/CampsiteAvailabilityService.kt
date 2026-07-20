package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCapabilitiesDto
import ca.floo.roadtrip.model.api.PoiCampsitesAvailabilityResponseDto
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.repo.CampsiteRepo
import java.time.LocalDate

private const val EMPTY_WINDOW_DEFAULT_DAYS = 7
private const val EMPTY_WINDOW_MAX_DAYS = 60
private const val EMPTY_WINDOW_HORIZON_DAYS = 365

internal class CampsiteAvailabilityService(
    private val campsitesRepo: CampsiteRepo,
    private val composer: CampsiteAvailabilityComposer,
    private val dateResolver: AvailabilityDateResolver,
    private val watchCapabilityService: WatchCapabilityService,
) {
    suspend fun poiCampsitesAvailability(
        poiId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        siteTypes: List<String>,
    ): PoiCampsitesAvailabilityResponseDto {
        val watchScopeCampsites = campsitesRepo.findByPoi(poiId)
        val watchCapabilities = watchCapabilitiesFor(watchScopeCampsites, watchCapabilityService)
        val campsites = watchScopeCampsites.filterBySiteTypes(siteTypes)
        if (campsites.isEmpty()) {
            val (start, end) = displayWindow(poiId, startDate, endDate, dateResolver)
            return emptyPoiAvailability(poiId, start, end, watchCapabilities)
        }

        val availability =
            composer.availabilityFor(
                campsites = campsites,
                startDate = startDate,
                endDate = endDate,
            )
        val firstAvailability = availability.firstOrNull()
        if (firstAvailability != null) {
            return PoiCampsitesAvailabilityResponseDto(
                poiId = poiId,
                startDate = firstAvailability.startDate,
                endDate = firstAvailability.endDate,
                watchCapabilities = watchCapabilities,
                campsites = availability,
            )
        }

        val (fallbackStart, fallbackEnd) = displayWindow(poiId, startDate, endDate, dateResolver)
        return PoiCampsitesAvailabilityResponseDto(
            poiId = poiId,
            startDate = fallbackStart.toString(),
            endDate = fallbackEnd.toString(),
            watchCapabilities = watchCapabilities,
            campsites = availability,
        )
    }
}

private fun emptyPoiAvailability(
    poiId: Long,
    startDate: LocalDate,
    endDate: LocalDate,
    watchCapabilities: AvailabilityWatchCapabilitiesDto,
): PoiCampsitesAvailabilityResponseDto =
    PoiCampsitesAvailabilityResponseDto(
        poiId = poiId,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        watchCapabilities = watchCapabilities,
        campsites = emptyList(),
    )

private fun watchCapabilitiesFor(
    campsites: List<Campsite>,
    capabilities: WatchCapabilityService,
): AvailabilityWatchCapabilitiesDto = capabilities.capabilitiesFor(campsites)

private fun displayWindow(
    poiId: Long,
    startDate: LocalDate?,
    endDate: LocalDate?,
    dateResolver: AvailabilityDateResolver,
): Pair<LocalDate, LocalDate> {
    val dateContext = dateResolver.contextForPoi(poiId)
    val window =
        dateResolver.resolveWindow(
            startDate = startDate,
            endDate = endDate,
            context = dateContext,
            bookingHorizonDays = EMPTY_WINDOW_HORIZON_DAYS,
            maxDays = EMPTY_WINDOW_MAX_DAYS,
            defaultDays = EMPTY_WINDOW_DEFAULT_DAYS,
        )
    return window.startDate to window.endDate
}
