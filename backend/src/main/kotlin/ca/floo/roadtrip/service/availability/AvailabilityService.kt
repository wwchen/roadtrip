package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.AvailabilityResponseDto
import ca.floo.roadtrip.models.domain.ReservableId
import java.time.LocalDate

interface AvailabilityService {
    suspend fun getByRid(
        rid: ReservableId,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean = false,
    ): AvailabilityResponseDto

    suspend fun getByRids(
        rids: List<ReservableId>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean = false,
    ): List<AvailabilityResponseDto>
}
