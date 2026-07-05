package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseDto
import java.time.LocalDate

internal interface AvailabilityQueryService {
    suspend fun poiReservablesAvailability(
        poiId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        siteTypes: List<String>,
    ): PoiReservablesAvailabilityResponseDto
}
