package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.domain.CampgroundSchedule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The check-in window a campground's sites inherit, as the API serves it. */
@Serializable
data class ScheduleDto(
    @SerialName("check_in") val checkIn: String? = null,
    @SerialName("check_out") val checkOut: String? = null,
) {
    companion object {
        fun from(schedule: CampgroundSchedule): ScheduleDto =
            ScheduleDto(
                checkIn = schedule.checkIn,
                checkOut = schedule.checkOut,
            )
    }
}
