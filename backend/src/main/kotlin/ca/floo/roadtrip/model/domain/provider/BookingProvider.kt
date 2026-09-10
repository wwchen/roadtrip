package ca.floo.roadtrip.model.domain.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `id` is the stored and API value, including inside [BookingAlias]. */
@Serializable
enum class BookingProvider(
    val id: String,
) {
    @SerialName("recgov")
    RECGOV("recgov"),

    @SerialName("campflare")
    CAMPFLARE("campflare"),

    @SerialName("aspira")
    ASPIRA("aspira"),

    @SerialName("reserveamerica")
    RESERVEAMERICA("reserveamerica"),

    @SerialName("reservecalifornia")
    RESERVECALIFORNIA("reservecalifornia"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): BookingProvider = byId[id] ?: error("Unknown booking provider: $id")

        fun fromIdOrNull(id: String): BookingProvider? = byId[id]
    }
}
