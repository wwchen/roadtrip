package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `campgrounds.default_campsite_schedule` JSONB object: the check-in window sites inherit. */
@Serializable
data class CampgroundSchedule(
    @SerialName("check_in") val checkIn: String? = null,
    @SerialName("check_out") val checkOut: String? = null,
)
