package ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica

data class ReserveAmericaTenant(
    val source: String,
    val host: String,
    val contractCode: String,
    val bookingHorizonDays: Int,
)
