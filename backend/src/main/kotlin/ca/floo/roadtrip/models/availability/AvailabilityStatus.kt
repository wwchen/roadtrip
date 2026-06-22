package ca.floo.roadtrip.models.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AvailabilityStatus(
    val wireValue: String,
) {
    @SerialName("first_come")
    FIRST_COME("first_come"),

    @SerialName("reserved")
    RESERVED("reserved"),

    @SerialName("available")
    AVAILABLE("available"),

    @SerialName("closed")
    CLOSED("closed"),

    @SerialName("unknown")
    UNKNOWN("unknown"),
    ;

    val isOnlineBookable: Boolean
        get() = this == AVAILABLE

    companion object {
        fun parse(raw: String?): AvailabilityStatus = entries.firstOrNull { it.wireValue == raw?.lowercase() } ?: UNKNOWN
    }
}
