package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.AvailabilityWatchCapabilitiesDto
import ca.floo.roadtrip.models.api.PoiCampsitesAvailabilityResponseDto
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import java.time.LocalDate

private const val EMPTY_WINDOW_DEFAULT_DAYS = 7
private const val EMPTY_WINDOW_MAX_DAYS = 60
private const val EMPTY_WINDOW_HORIZON_DAYS = 365

internal class CampsiteAvailabilityService(
    private val providerRefs: CampsiteProviderRepo,
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
        val watchScopeCampsites = campsitesRepo.findAvailabilityTargetsByPoi(poiId)
        val watchCapabilities = watchCapabilitiesFor(watchScopeCampsites, watchCapabilityService)
        val campsites = watchScopeCampsites.filterBySiteTypes(siteTypes)
        if (campsites.isEmpty()) {
            val (start, end) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
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

        val (fallbackStart, fallbackEnd) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
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
    campsites: List<CampsiteAvailabilityTarget>,
    capabilities: WatchCapabilityService,
): AvailabilityWatchCapabilitiesDto {
    val bookingActions = capabilities.supportedBookingActions(campsites)
    return AvailabilityWatchCapabilitiesDto(
        triggerKinds = capabilities.supportedTriggerKinds(campsites),
        bookingActions = BookingAction.entries.filter { it in bookingActions }.map { it.wireValue },
    )
}

private fun displayWindow(
    poiId: Long,
    startDate: LocalDate?,
    endDate: LocalDate?,
    providerRefs: CampsiteProviderRepo,
    dateResolver: AvailabilityDateResolver,
): Pair<LocalDate, LocalDate> {
    val row = providerRefs.findDateContext(poiId) ?: throw AvailabilityServiceError.NotFound
    val dateContext = dateResolver.context(lat = row.lat, lng = row.lng)
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
