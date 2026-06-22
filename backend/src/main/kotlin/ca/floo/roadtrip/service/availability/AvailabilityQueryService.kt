package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.BulkAvailabilityResponseDto
import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseDto
import java.time.LocalDate

internal interface AvailabilityQueryService {
    suspend fun poiReservablesAvailability(
        poiId: Long,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean,
        siteTypes: List<String>,
    ): PoiReservablesAvailabilityResponseDto

    suspend fun bulkAvailability(
        ids: List<Long>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): BulkAvailabilityResponseDto
}
