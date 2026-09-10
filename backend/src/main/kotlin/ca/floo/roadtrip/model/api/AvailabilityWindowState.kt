package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The window-level outcome of a fused campground availability read. */
@Serializable
enum class AvailabilityWindowState(
    val wireValue: String,
) {
    @SerialName("success")
    SUCCESS("success"),

    @SerialName("empty")
    EMPTY("empty"),

    @SerialName("closed_for_season")
    CLOSED_FOR_SEASON("closed_for_season"),
}
