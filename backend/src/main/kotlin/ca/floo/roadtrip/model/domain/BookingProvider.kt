package ca.floo.roadtrip.model.domain

enum class BookingProvider(
    val id: String,
) {
    RECGOV("recgov"),
    CAMPFLARE("campflare"),
    ASPIRA("aspira"),
    RESERVEAMERICA("reserveamerica"),
    RESERVECALIFORNIA("reservecalifornia"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): BookingProvider = byId[id] ?: error("Unknown booking provider: $id")

        fun fromIdOrNull(id: String): BookingProvider? = byId[id]
    }
}
