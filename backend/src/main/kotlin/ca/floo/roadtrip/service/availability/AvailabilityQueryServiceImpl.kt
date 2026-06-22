package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseDto
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import java.time.LocalDate

private const val EMPTY_WINDOW_DEFAULT_DAYS = 7
private const val EMPTY_WINDOW_MAX_DAYS = 60
private const val EMPTY_WINDOW_HORIZON_DAYS = 365

internal class AvailabilityQueryServiceImpl(
    private val providerRefs: CampsiteProviderRepo,
    private val reservablesRepo: ReservableRepo,
    private val availabilityService: AvailabilityService,
    private val dateResolver: AvailabilityDateResolver,
) : AvailabilityQueryService {
    override suspend fun poiReservablesAvailability(
        poiId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean,
        siteTypes: List<String>,
    ): PoiReservablesAvailabilityResponseDto {
        val reservables =
            reservablesRepo
                .findByPoi(poiId, ReservableType.SITE)
                .filterAvailabilitySiteTypes(siteTypes)
        if (reservables.isEmpty()) {
            val (start, end) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
            return PoiReservablesAvailabilityResponseDto(
                poiId = poiId,
                startDate = start.toString(),
                endDate = end.toString(),
                reservables = emptyList(),
            )
        }

        val availability =
            availabilityService.getByRids(
                rids = reservables.map { it.rid },
                startDate = startDate,
                endDate = endDate,
                force = force,
            )
        val firstAvailability = availability.firstOrNull()
        if (firstAvailability != null) {
            return PoiReservablesAvailabilityResponseDto(
                poiId = poiId,
                startDate = firstAvailability.startDate,
                endDate = firstAvailability.endDate,
                reservables = availability,
            )
        }

        val (fallbackStart, fallbackEnd) = displayWindow(poiId, startDate, endDate, providerRefs, dateResolver)
        return PoiReservablesAvailabilityResponseDto(
            poiId = poiId,
            startDate = fallbackStart.toString(),
            endDate = fallbackEnd.toString(),
            reservables = availability,
        )
    }
}

private fun List<Reservable>.filterAvailabilitySiteTypes(siteTypes: Collection<String>): List<Reservable> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.siteType != null && it.siteType in allowed }
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
