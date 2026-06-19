package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import java.time.LocalDate

interface AvailabilityService {
    suspend fun getByRid(
        rid: ReservableId,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean,
    ): AvailabilityResponseDto

    suspend fun getByRids(
        rids: List<ReservableId>,
        startDate: LocalDate?,
        endDate: LocalDate?,
        force: Boolean,
    ): List<AvailabilityResponseDto>
}

sealed class AvailabilityServiceError(
    val error: String,
) : RuntimeException(error) {
    object BadDateWindow : AvailabilityServiceError("bad_date_window")

    object NotFound : AvailabilityServiceError("not_found")

    object UnknownCampground : AvailabilityServiceError("unknown_campground")
}
