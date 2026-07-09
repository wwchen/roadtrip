package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.PoiCampsitesAvailabilityResponseDto
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
) {
    suspend fun poiCampsitesAvailability(
        poiId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        siteTypes: List<String>,
    ): PoiCampsitesAvailabilityResponseDto {
        val campsites =
            campsitesRepo
                .findByPoi(poiId)
                .filterBySiteTypes(siteTypes)
        if (campsites.isEmpty()) {
            val (start, end) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
            return emptyPoiAvailability(poiId, start, end)
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
                campsites = availability,
            )
        }

        val (fallbackStart, fallbackEnd) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
        return PoiCampsitesAvailabilityResponseDto(
            poiId = poiId,
            startDate = fallbackStart.toString(),
            endDate = fallbackEnd.toString(),
            campsites = availability,
        )
    }
}

private fun emptyPoiAvailability(
    poiId: Long,
    startDate: LocalDate,
    endDate: LocalDate,
): PoiCampsitesAvailabilityResponseDto =
    PoiCampsitesAvailabilityResponseDto(
        poiId = poiId,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        campsites = emptyList(),
    )

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
