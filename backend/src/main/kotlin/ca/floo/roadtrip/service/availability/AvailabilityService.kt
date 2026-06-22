package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import java.time.LocalDate
import java.time.ZoneId

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
    sealed class BadDateWindow(
        error: String,
    ) : AvailabilityServiceError(error) {
        data class StartBeforeEarliest(
            val earliestDate: LocalDate,
            val timeZone: ZoneId,
        ) : BadDateWindow("start_before_earliest")

        object EndBeforeStart : BadDateWindow("end_before_start")

        data class WindowTooLong(
            val maxDays: Int,
        ) : BadDateWindow("window_too_long")

        data class BeyondBookingHorizon(
            val latestDate: LocalDate,
        ) : BadDateWindow("beyond_booking_horizon")

        object Invalid : BadDateWindow("bad_date_window")
    }

    object NotFound : AvailabilityServiceError("not_found")

    object UnknownCampground : AvailabilityServiceError("unknown_campground")
}
